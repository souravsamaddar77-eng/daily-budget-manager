package dev.daybudget.domain

import java.math.BigInteger

/** Exact decimal-to-paise conversion; no floating point is used anywhere in the ledger. */
object Money {
    fun parse(text: String, allowNegative: Boolean = false): Long {
        var value = text.trim().removePrefix("₹").trim().replace('−', '-')
        val negative = value.startsWith("-")
        if (negative && !allowNegative) throw BudgetException("Enter an amount of zero or more.")
        if (value.startsWith("-") || value.startsWith("+")) value = value.drop(1)
        if (!Regex("[0-9,]+(?:\\.[0-9]{1,2})?").matches(value)) {
            throw BudgetException("Enter a number with at most two decimal places, such as 125.50.")
        }
        val parts = value.split('.')
        val whole = parts[0]
        if (',' in whole && !Regex("(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,2}(?:,[0-9]{2})*,[0-9]{3})").matches(whole)) {
            throw BudgetException("Check the commas in the amount, or enter it without commas.")
        }
        val paise = BigInteger(whole.replace(",", "")).multiply(BigInteger.valueOf(100))
            .add(BigInteger(parts.getOrElse(1) { "" }.padEnd(2, '0')))
        return exact(if (negative) paise.negate() else paise)
    }

    fun format(paise: Long, includeSymbol: Boolean = true): String {
        val magnitude = BigInteger.valueOf(paise).abs().toString().padStart(3, '0')
        val rupees = magnitude.dropLast(2)
        val grouped = if (rupees.length <= 3) rupees else {
            rupees.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + rupees.takeLast(3)
        }
        return (if (paise < 0) "−" else "") + (if (includeSymbol) "₹" else "") + grouped + "." + magnitude.takeLast(2)
    }

    internal fun exact(value: BigInteger): Long = try {
        value.longValueExact()
    } catch (_: ArithmeticException) {
        throw BudgetException("This amount or total is too large. Reduce it before saving.")
    }

    internal fun sum(values: Iterable<Long>): Long = exact(values.fold(BigInteger.ZERO) { a, b -> a.add(BigInteger.valueOf(b)) })
    internal fun combine(vararg values: Pair<Long, Int>): Long = exact(values.fold(BigInteger.ZERO) { total, (value, sign) ->
        total.add(BigInteger.valueOf(value).multiply(BigInteger.valueOf(sign.toLong())))
    })
}
