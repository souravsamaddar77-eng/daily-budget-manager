package dev.daybudget.notifications

import dev.daybudget.domain.Money
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

data class ParsedPayment(
    val amount: Long,
    val merchant: String,
    val sourcePackage: String,
    val postedAt: Instant,
    val referenceHash: String?,
    val captureId: String,
    val highConfidence: Boolean,
    val reviewReason: String,
)

/** Conservative English-only patterns; no network, raw-text retention, or bank SMS access. */
object PaymentNotificationParser {
    private val currency = Regex("(?:₹\\s*|\\bINR\\s*|\\bRs\\.?\\s*)([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?![0-9.A-Za-z])", RegexOption.IGNORE_CASE)
    private val currencyPrefix = Regex("₹|\\bINR\\b|\\bRs\\.?(?=\\s|[0-9])", RegexOption.IGNORE_CASE)
    private val unsafe = Regex("\\b(?:otp|one[- ]time|verification code|request(?:ed)?|collect request|pending|processing|initiated|failed|declined|unsuccessful|cancelled|canceled|reversed|refunded|refund|credited|received|cashback|reward|scheduled|reminder|due|pay now)\\b", RegexOption.IGNORE_CASE)
    private val outgoing = Regex(
        "(?:^|\\n)\\s*(?:you\\s+(?:have\\s+)?)?(?:paid|sent|debited)(?:\\s+(?:an?\\s+)?amount\\s+of|\\s+(?:by|for|with))?\\s+(?:₹|INR|Rs\\.?)\\s*[0-9]" +
            "|(?:^|\\n)\\s*(?:₹|INR|Rs\\.?)\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s+(?:was\\s+|has\\s+been\\s+)?(?:paid|sent|debited)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val incomingDirection = Regex("\\bpaid\\s+by\\b|\\bsent\\s+you\\b|\\bto\\s+you\\b", RegexOption.IGNORE_CASE)
    private val nonSpending = Regex("\\b(?:transfer(?:red)?|self|own\\s+account|my\\s+account|atm|cash\\s+withdrawal|withdrawn)\\b", RegexOption.IGNORE_CASE)
    private val merchant = Regex("\\bto\\s+(.+?)(?=\\s+(?:on|using|via|from|upi|transaction|txn|ref(?:erence)?|utr|a/c|account)\\b|\\.\\s|[\\n;]|$)", setOf(RegexOption.IGNORE_CASE))
    private val reference = Regex("\\b(?:upi\\s+(?:transaction\\s+id|ref(?:erence)?(?:\\s+(?:id|no))?)|utr|transaction\\s+(?:id|ref(?:erence)?)|txn\\s+id|ref(?:erence)?\\s*(?:no|id))\\s*[:#.-]?\\s*([A-Za-z0-9]{8,40})\\b(?!@)", RegexOption.IGNORE_CASE)

    fun parse(packageName: String, title: String, text: String, postedAt: Instant, notificationKey: String,
              identityHasher: (String) -> String = ::hash): ParsedPayment? {
        if (SUPPORTED_PAYMENT_APPS.none { it.packageName == packageName }) return null
        // Bound ephemeral work before any regular expressions; oversized templates need manual entry.
        if (title.length + text.length > 8192) return null
        val content = "$title\n$text".replace('\u00a0', ' ')
        if (unsafe.containsMatchIn(content) || incomingDirection.containsMatchIn(content) ||
            nonSpending.containsMatchIn(content) || !outgoing.containsMatchIn(content)) return null
        val amountMatches = currency.findAll(content).toList()
        if (amountMatches.size != currencyPrefix.findAll(content).count()) return null
        val parsedAmounts = amountMatches.map { match ->
            runCatching { Money.parse(match.groupValues[1]) }.getOrNull()?.takeIf { it > 0 } ?: return null
        }
        val amounts = parsedAmounts.distinct()
        if (amounts.size != 1) return null // Never choose between conflicting amounts.
        val references = reference.findAll(content).map { it.groupValues[1].uppercase(Locale.ROOT) }
            .filter { it.any(Char::isDigit) }.distinct().toList()
        val referenceHash = references.singleOrNull()?.let { identityHasher("upi-reference:$it") }
        val merchantName = merchant.find(content)?.groupValues?.get(1)?.let(::safeMerchant).orEmpty()
        val complete = referenceHash != null && merchantName.isNotBlank()
        val reason = when {
            references.size > 1 -> "Several payment references were shown. Check this payment before adding it."
            referenceHash == null -> "No unique payment reference was shown. Check for an existing entry before adding it."
            merchantName.isBlank() -> "The merchant could not be read safely. Enter or check the merchant."
            else -> "Check the amount and merchant before adding this payment."
        }
        val id = if (referenceHash != null) "upi-$referenceHash" else
            "review-${identityHasher("$packageName|$notificationKey|${postedAt.toEpochMilli()}")}"
        return ParsedPayment(amounts.single(), merchantName, packageName, postedAt, referenceHash, id, complete, reason)
    }

    internal fun safeMerchant(value: String): String {
        val cleaned = value.trim().trimEnd('.', ',', ':', '-', ' ').replace(Regex("\\s+"), " ")
        // Never persist an account number, phone number, or UPI/email identifier as merchant text.
        if ('@' in cleaned || '/' in cleaned || ':' in cleaned || cleaned.count(Char::isDigit) >= 6 ||
            Regex("(?:[Xx*][ -]*){3,}").containsMatchIn(cleaned) || cleaned.length > 80) return ""
        if (Regex("\\b(?:account|a/c|upi id|mobile)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)) return ""
        return cleaned.takeIf { it.any(Char::isLetter) }.orEmpty()
    }

    internal fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
