package com.roomrentmanager.domain.finance

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money is stored and calculated as whole paise in a [Long]. Rupees appear only at the UI
 * boundary (parsing user input, formatting for display) and when importing legacy v1 data.
 *
 * Rounding rule: where a value has more than two decimal places (only legacy v1 `REAL` amounts),
 * it is rounded to the nearest paisa with [RoundingMode.HALF_UP].
 */
object Money {
    const val CURRENCY_CODE = "INR"

    /** Largest amount accepted from input: ₹100 crore. Guards against typos and overflow. */
    const val MAX_PAISE: Long = 1_000_000_000_00L

    private val PLAIN_AMOUNT = Regex("""\d{1,12}(\.\d{1,2})?""")

    /** Converts a rupee amount to paise, rounding HALF_UP. Throws if the result does not fit. */
    fun rupeesToPaise(value: BigDecimal): Long =
        value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()

    fun rupeesToPaise(value: String): Long = rupeesToPaise(value.trim().toBigDecimal())

    fun paiseToRupees(value: Long): BigDecimal = value.toBigDecimal().movePointLeft(2)

    /**
     * Strictly parses user input such as `1500`, `1500.5` or `1,500.50` into paise.
     * Returns null for blank, malformed, negative, more than two decimals, or above [MAX_PAISE].
     * Zero is allowed; callers that need a positive amount must check it.
     */
    fun parseRupees(input: String): Long? {
        val text = input.trim().replace(",", "")
        if (!PLAIN_AMOUNT.matches(text)) return null
        val paise = rupeesToPaise(text.toBigDecimal())
        return paise.takeIf { it in 0..MAX_PAISE }
    }

    /**
     * Converts a legacy v1 `REAL` rupee amount to paise. Uses the shortest decimal representation
     * of the double (so 0.285 is treated as 0.285, not 0.28499999...) and then HALF_UP.
     * Returns null for NaN, infinity or values that do not fit.
     */
    fun legacyRupeesToPaise(value: Double): Long? {
        if (value.isNaN() || value.isInfinite()) return null
        return runCatching { rupeesToPaise(BigDecimal(value.toString())) }.getOrNull()
    }

    /** Plain editable text: 150000 -> "1500", 150050 -> "1500.50". */
    fun toInputText(paise: Long): String {
        val rupees = paiseToRupees(paise)
        return if (paise % 100 == 0L) rupees.setScale(0).toPlainString() else rupees.setScale(2).toPlainString()
    }

    /**
     * Display text in INR with Indian digit grouping, e.g. ₹1,23,456.50 or -₹500.00.
     * Done by hand because java.text grouping differs between the JVM and Android's ICU.
     */
    fun format(paise: Long): String {
        val abs = paiseToRupees(paise).abs().setScale(2)
        val (whole, fraction) = abs.toPlainString().split('.')
        val grouped = if (whole.length <= 3) {
            whole
        } else {
            val head = whole.dropLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
        }
        return (if (paise < 0) "-" else "") + "₹" + grouped + "." + fraction
    }
}
