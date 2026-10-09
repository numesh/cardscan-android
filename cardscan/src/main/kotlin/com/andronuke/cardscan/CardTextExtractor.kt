package com.andronuke.cardscan

import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.abs

/** Converts OCR lines into conservative card candidates without inventing digits. */
internal object CardTextExtractor {
    private val numberPattern = Pattern.compile("(?<![0-9])([0-9][0-9 -]{11,30}[0-9])(?![0-9])")
    private val expiryPattern = Pattern.compile("(?<![0-9])([0-9]{1,2})\\s*[/.-]\\s*([0-9]{2}|20[0-9]{2})(?![0-9])")
    private val spacedExpiryPattern = Pattern.compile("(?<![0-9])([0-9]{1,2})\\s+([0-9]{2}|20[0-9]{2})(?![0-9])")
    private val compactExpiryPattern = Pattern.compile("(?<![0-9])([0-9]{2})([0-9]{2})(?![0-9])")

    const val EXPIRY_OK = 0
    const val EXPIRY_INVALID = 1
    const val EXPIRY_EXPIRED = 2
    const val EXPIRY_AMBIGUOUS = 3

    data class Line(val text: String, val top: Int, val left: Int)
    data class Scan(
        val number: String?,
        val detectedNumber: String?,
        val expiry: String?,
        val detectedExpiry: String?,
        val hasText: Boolean,
        val unsupportedNetwork: Boolean,
        val invalidNumber: Boolean,
        val expiryIssue: Int
    )

    private data class ExpiryHit(val value: String, val distance: Int, val labelled: Boolean)

    fun extract(lines: List<Line>, currentYear: Int, currentMonth: Int): Scan {
        var number: String? = null
        var detectedNumber: String? = null
        var numberTop = 0
        var unsupported = false
        var invalidNumber = false
        // ML Kit may split four-digit groups into separate lines at nearly the same height.
        val numberLines = joinedRows(lines)
        for (line in numberLines) {
            val matcher = numberPattern.matcher(line.text)
            while (matcher.find()) {
                val digits = matcher.group(1)!!.replace(" ", "").replace("-", "")
                if (digits.length !in 13..19) continue
                if (detectedNumber == null) detectedNumber = digits
                if (!luhn(digits)) {
                    invalidNumber = true
                    continue
                }
                if (!supportedNetwork(digits)) {
                    unsupported = true
                    continue
                }
                if (number != null && number != digits) {
                    return Scan(null, detectedNumber, null, null, true, false, true, EXPIRY_OK)
                }
                number = digits
                detectedNumber = digits
                numberTop = line.top
            }
        }

        val dates = mutableListOf<ExpiryHit>()
        val detectedExpiry = arrayOfNulls<String>(1)
        var expiryIssue = EXPIRY_OK
        for (line in numberLines) {
            val labelled = Regex("EXP|VALID|THRU|GOOD").containsMatchIn(line.text.uppercase(Locale.ROOT))
            val distance = if (number == null) 0 else abs(line.top - numberTop)
            expiryIssue = maxOf(expiryIssue, addDates(dates, expiryPattern.matcher(line.text),
                currentYear, currentMonth, distance, labelled, detectedExpiry))
            val onlyDigits = line.text.replace(Regex("[^0-9]"), "")
            if (labelled || onlyDigits.length <= 6) {
                expiryIssue = maxOf(expiryIssue, addDates(dates, spacedExpiryPattern.matcher(line.text),
                    currentYear, currentMonth, distance, labelled, detectedExpiry))
            }
            if (labelled) {
                expiryIssue = maxOf(expiryIssue, addDates(dates, compactExpiryPattern.matcher(line.text),
                    currentYear, currentMonth, distance, true, detectedExpiry))
            }
        }
        // A printed expiry label is stronger evidence than an unlabelled date elsewhere.
        dates.sortWith(compareByDescending<ExpiryHit> { it.labelled }.thenBy { it.distance })
        var expiry: String? = null
        if (dates.isNotEmpty()) {
            val first = dates.first()
            val different = dates.firstOrNull { it.value != first.value }
            // Never choose arbitrarily between similarly ranked, distinct dates.
            val ambiguous = different != null && different.labelled == first.labelled &&
                abs(different.distance - first.distance) < 40
            if (ambiguous) expiryIssue = EXPIRY_AMBIGUOUS else expiry = first.value
        }
        if (expiry != null) {
            expiryIssue = EXPIRY_OK
            detectedExpiry[0] = expiry
        }
        return Scan(number, detectedNumber, expiry, detectedExpiry[0],
            lines.isNotEmpty(), if (number == null) unsupported else false,
            if (number == null) invalidNumber else false, expiryIssue)
    }

    /** Includes joined same-row OCR pieces, useful when ML Kit separates printed groups. */
    fun joinedRows(lines: List<Line>): List<Line> {
        val result = lines.toMutableList()
        for (anchor in lines) {
            val row = lines.filter { abs(it.top - anchor.top) <= 24 }.sortedBy { it.left }
            if (row.size >= 2) result += Line(row.joinToString(" ") { it.text }, anchor.top, row.first().left)
        }
        return result
    }

    private fun addDates(
        dates: MutableList<ExpiryHit>, matcher: Matcher, currentYear: Int, currentMonth: Int,
        distance: Int, labelled: Boolean, detectedExpiry: Array<String?>
    ): Int {
        var issue = EXPIRY_OK
        while (matcher.find()) {
            val month = matcher.group(1)!!.toInt()
            val yearText = matcher.group(2)!!
            if (detectedExpiry[0] == null) {
                detectedExpiry[0] = "%02d/%s".format(Locale.US, month, yearText.takeLast(2))
            }
            if (month !in 1..12) {
                issue = maxOf(issue, EXPIRY_INVALID)
                continue
            }
            var year = yearText.toInt()
            // A past two-digit year is expired; do not reinterpret it as a future century,
            // except near a century rollover (for example, 2099 followed by 2101).
            if (yearText.length == 2) {
                year += currentYear / 100 * 100
                if (year < currentYear && currentYear % 100 >= 80 && year % 100 <= 20) year += 100
            }
            if (year < currentYear || (year == currentYear && month < currentMonth)) {
                issue = maxOf(issue, EXPIRY_EXPIRED)
                continue
            }
            if (year > currentYear + 20) {
                issue = maxOf(issue, EXPIRY_INVALID)
                continue
            }
            dates += ExpiryHit("%02d/%02d".format(Locale.US, month, year % 100), distance, labelled)
        }
        return issue
    }

    fun isAcceptedNumber(digits: String?): Boolean = digits != null &&
        digits.matches(Regex("[0-9]{13,19}")) && luhn(digits) && supportedNetwork(digits)

    private fun luhn(digits: String): Boolean {
        var sum = 0
        var doubleNext = false
        for (char in digits.reversed()) {
            var n = char - '0'
            if (doubleNext) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            doubleNext = !doubleNext
        }
        return sum % 10 == 0
    }

    // R&D defaults. The host payment flow must supply its supported-card rules.
    private fun supportedNetwork(digits: String): Boolean {
        val length = digits.length
        if (digits.startsWith('4')) return length == 13 || length == 16 || length == 19
        val two = digits.substring(0, 2).toInt()
        val four = digits.substring(0, 4).toInt()
        if (two in 51..55 || four in 2221..2720) return length == 16
        if (two == 34 || two == 37) return length == 15
        return false
    }
}
