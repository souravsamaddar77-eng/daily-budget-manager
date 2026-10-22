package dev.daybudget.notifications

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PaymentNotificationParserTest {
    private val at = Instant.parse("2026-10-22T10:00:00Z")
    private val phonePe = "com.phonepe.app"
    private fun parse(text: String, title: String = "Payment successful", source: String = phonePe, key: String = "key") =
        PaymentNotificationParser.parse(source, title, text, at, key)

    @Test fun exactOutgoingPaymentWithReferenceIsEligibleForAutoRecording() {
        val result = requireNotNull(parse("You paid ₹1,234.50 to Corner Cafe. UPI transaction ID: 123456789012"))
        assertEquals(123_450L, result.amount)
        assertEquals("Corner Cafe", result.merchant)
        assertTrue(result.highConfidence)
        assertEquals(64, result.referenceHash!!.length)
        assertFalse(result.toString().contains("123456789012"))
    }

    @Test fun supportedSourcesAndCurrencySpellingsWorkWithoutFloatingPoint() {
        for (app in SUPPORTED_PAYMENT_APPS) {
            val result = requireNotNull(parse("Paid Rs. 0.29 to Tea Shop\nUTR: ABC123456789", source = app.packageName))
            assertEquals(29L, result.amount)
            assertTrue(result.highConfidence)
        }
        assertEquals(12_345_678L, requireNotNull(parse("Sent INR 1,23,456.78 to Shop\nTransaction ID: ABC123456" )).amount)
    }

    @Test fun unrelatedPackagesAreRejectedEvenWithAnExactPaymentTemplate() {
        assertNull(parse("Paid ₹100 to Shop UTR 123456789012", source = "com.example.fake"))
    }

    @Test fun requestsFailuresCreditsAndOtpNeverBecomeExpenses() {
        listOf("OTP", "verification code", "payment request", "pending", "processing", "initiated", "failed", "declined",
            "unsuccessful", "cancelled", "reversed", "refunded", "credited", "received", "cashback", "pay now",
            "transfer", "self", "own account", "ATM", "cash withdrawal").forEach { unsafe ->
            assertNull(unsafe, parse("$unsafe. Paid ₹100 to Shop. UTR: 123456789012"))
        }
    }

    @Test fun amountAloneAndMarketingAreNotOutgoingPayments() {
        assertNull(parse("₹500 at your favourite shop", title = "Special offer"))
        assertNull(parse("Save ₹500", title = "Great deal"))
        assertNull(parse("₹500 from Sam to you. UTR 123456789012"))
        assertNull(parse("Sam paid ₹500 to you. UTR 123456789012"))
        assertNull(parse("Sam paid ₹500 to Smith. UTR 123456789012"))
        assertNull(parse("Paid offer ₹500 to Shop UTR 123456789012"))
        assertNull(parse("Available balance ₹500 to Shop UTR 123456789012", title = "Payment sent"))
    }

    @Test fun conflictingAmountsZeroOverflowAndInvalidDecimalsAreRejected() {
        listOf("₹100 and ₹200", "₹0", "₹1.234", "₹1e3", "₹1,23.00", "₹92233720368547758.08").forEach { amount ->
            assertNull(amount, parse("Paid $amount to Shop UTR 123456789012"))
        }
        listOf("₹1,00", "₹0", "₹1.234", "₹1e3", "₹-50", "₹92233720368547758.08").forEach { invalid ->
            assertNull(invalid, parse("You paid $invalid to Cafe. Available balance ₹500. UTR 123456789012"))
        }
    }

    @Test fun repeatedTitleAmountDoesNotCreateAFalseAmbiguity() {
        val result = requireNotNull(parse("Paid ₹200 to Shop UTR 123456789012", "₹200 paid"))
        assertEquals(20_000L, result.amount)
        assertTrue(result.highConfidence)
    }

    @Test fun missingReferenceRequiresReviewAndExactReplaysKeepTheSameIdentity() {
        val first = requireNotNull(parse("Paid ₹50 to Tea Shop"))
        assertFalse(first.highConfidence)
        assertNull(first.referenceHash)
        assertTrue(first.reviewReason.contains("existing entry"))
        assertEquals(first.captureId, requireNotNull(parse("Paid ₹50 to Tea Shop")).captureId)
        assertNotEquals(first.captureId, requireNotNull(parse("Paid ₹50 to Tea Shop", key = "another")).captureId)
    }

    @Test fun referenceIdentitySurvivesNotificationUpdatesAndSourceChanges() {
        val original = requireNotNull(parse("Paid ₹50 to Tea Shop UTR ABC123456789"))
        val update = requireNotNull(parse("Paid ₹50 to Tea Shop. UTR: abc123456789", key = "replacement"))
        val crossSource = requireNotNull(parse("Paid ₹50 to Tea Shop UTR ABC123456789", source = "net.one97.paytm"))
        assertEquals(original.captureId, update.captureId)
        assertEquals(original.captureId, crossSource.captureId)
    }

    @Test fun accountPhoneAndUpiIdentifiersAreNeverKeptAsMerchantNames() {
        listOf("person@bank", "9876543210", "98765 43210", "XXXX 1234", "account 1234").forEach { merchant ->
            val result = requireNotNull(parse("Paid ₹10 to $merchant UTR 123456789012"))
            assertEquals(merchant, "", result.merchant)
            assertFalse(result.highConfidence)
        }
    }

    @Test fun anUpiAddressIsNotATransactionReference() {
        val result = requireNotNull(parse("Paid ₹50 to Shop. UPI ID 12345678@bank"))
        assertFalse(result.highConfidence)
        assertNull(result.referenceHash)
    }

    @Test fun multipleReferencesRequireReview() {
        val result = requireNotNull(parse("Paid ₹50 to Shop UTR 123456789012 Transaction ID ABC123456789"))
        assertFalse(result.highConfidence)
        assertNull(result.referenceHash)
    }

    @Test fun longUnknownTemplatesFailClosed() {
        assertNull(parse("Paid ₹50 to Shop " + "a".repeat(8200)))
    }
}
