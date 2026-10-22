package dev.daybudget.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import dev.daybudget.domain.BudgetException
import java.time.Instant
import java.time.LocalDate
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

internal data class CaptureRecord(
    val candidate: PaymentCandidate,
    val autoRequested: Boolean,
    val generation: Long,
)

internal data class CaptureData(
    val settings: NotificationSettings = NotificationSettings(),
    val captureSinceMillis: Long = Long.MAX_VALUE,
    val generation: Long = 0,
    val captures: List<CaptureRecord> = emptyList(),
    val decisions: Map<String, String> = emptyMap(),
    val assignments: Map<String, String> = emptyMap(),
    val alertReceipts: Set<String> = emptySet(),
)

/** Append-only capture facts plus separate durable decisions, committed before any import. */
internal class NotificationCaptureStore(context: Context, name: String = "payment-notifications-v1") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** A random app-private key prevents cross-install correlation of predictable UPI references. */
    @Synchronized fun hashIdentity(value: String): String {
        val encoded = preferences.getString("identity-key", null)
        val key = if (encoded != null) Base64.getDecoder().decode(encoded) else ByteArray(32).also {
            val existing = read()
            if (existing.captures.isNotEmpty() || existing.decisions.isNotEmpty() || existing.assignments.isNotEmpty())
                throw BudgetException("Payment detection's private key is missing. Detection is paused to avoid duplicate expenses.")
            SecureRandom().nextBytes(it)
            if (!commitEdit { putString("identity-key", Base64.getEncoder().encodeToString(it)) })
                throw BudgetException("Payment detection could not save its private duplicate-check key.")
        }
        if (key.size != 32) throw BudgetException("Payment detection's private key is invalid. Detection is paused to avoid duplicate expenses.")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @Synchronized fun removeIdentityKey() {
        if (!commitEdit { remove("identity-key") }) throw BudgetException("Payment detection could not clear its private key.")
    }

    fun read(): CaptureData {
        val text = preferences.getString("state", null) ?: return CaptureData()
        try {
            val root = JSONObject(text)
            check(root.getInt("version") == 1)
            val settings = root.getJSONObject("settings")
            val captures = root.getJSONArray("captures")
            val decisions = root.getJSONObject("decisions")
            return CaptureData(
                settings = NotificationSettings(
                    captureEnabled = settings.getBoolean("captureEnabled"),
                    autoRecord = settings.getBoolean("autoRecord"),
                    targetBudgetId = settings.optString("targetBudgetId").ifBlank { null },
                    enabledPackages = settings.getJSONArray("enabledPackages").strings().toSet(),
                    spendingAlertsEnabled = settings.getBoolean("spendingAlertsEnabled"),
                    overspendAlertsEnabled = settings.getBoolean("overspendAlertsEnabled"),
                ),
                captureSinceMillis = root.getLong("captureSinceMillis"),
                generation = root.getLong("generation"),
                captures = (0 until captures.length()).map { index ->
                    val item = captures.getJSONObject(index)
                    CaptureRecord(PaymentCandidate(
                        id = item.getString("id"), amount = item.getLong("amount"),
                        merchant = item.getString("merchant"), sourcePackage = item.getString("sourcePackage"),
                        postedAt = Instant.ofEpochMilli(item.getLong("postedAt")), date = LocalDate.parse(item.getString("date")),
                        budgetId = item.getString("budgetId"), referenceHash = item.optString("referenceHash").ifBlank { null },
                        reviewReason = item.getString("reviewReason"),
                    ), item.getBoolean("autoRequested"), item.getLong("generation"))
                },
                decisions = decisions.keys().asSequence().associateWith { decisions.getString(it) },
                assignments = root.optJSONObject("assignments")?.let { assignments ->
                    assignments.keys().asSequence().associateWith { assignments.getString(it) }
                }.orEmpty(),
                alertReceipts = root.getJSONArray("alertReceipts").strings().toSet(),
            )
        } catch (_: Exception) {
            // Failing closed prevents damaged receipts from silently causing duplicate expenses.
            throw BudgetException("Payment detection data could not be read. Detection is paused; your budgets are unchanged.")
        }
    }

    fun write(data: CaptureData) {
        val settings = JSONObject().apply {
            put("captureEnabled", data.settings.captureEnabled)
            put("autoRecord", data.settings.autoRecord)
            put("targetBudgetId", data.settings.targetBudgetId.orEmpty())
            put("enabledPackages", JSONArray(data.settings.enabledPackages.sorted()))
            put("spendingAlertsEnabled", data.settings.spendingAlertsEnabled)
            put("overspendAlertsEnabled", data.settings.overspendAlertsEnabled)
        }
        val captures = JSONArray().apply {
            data.captures.forEach { record ->
                val candidate = record.candidate
                put(JSONObject().apply {
                    put("id", candidate.id); put("amount", candidate.amount); put("merchant", candidate.merchant)
                    put("sourcePackage", candidate.sourcePackage); put("postedAt", candidate.postedAt.toEpochMilli())
                    put("date", candidate.date.toString()); put("budgetId", candidate.budgetId)
                    put("referenceHash", candidate.referenceHash.orEmpty()); put("reviewReason", candidate.reviewReason)
                    put("autoRequested", record.autoRequested); put("generation", record.generation)
                })
            }
        }
        val root = JSONObject().apply {
            put("version", 1); put("settings", settings); put("captures", captures)
            put("captureSinceMillis", data.captureSinceMillis); put("generation", data.generation)
            put("decisions", JSONObject(data.decisions)); put("alertReceipts", JSONArray(data.alertReceipts.sorted()))
            put("assignments", JSONObject(data.assignments))
        }
        if (!commitEdit { putString("state", root.toString()) }) {
            throw BudgetException("Payment detection could not save its duplicate checks. Try again after freeing storage.")
        }
    }

    /** The Boolean result is required: importing must stop when the durable write fails. */
    @SuppressLint("UseKtx") // KTX edit returns Unit and discards commit failure; receipt writes must check it.
    private inline fun commitEdit(change: SharedPreferences.Editor.() -> Unit): Boolean {
        val editor = preferences.edit()
        editor.change()
        return editor.commit()
    }

    private fun JSONArray.strings() = (0 until length()).map(::getString)
}
