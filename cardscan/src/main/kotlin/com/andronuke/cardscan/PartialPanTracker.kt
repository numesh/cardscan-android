package com.andronuke.cardscan

import java.util.regex.Pattern

/** Keeps repeated, overlapping OCR fragments in memory for one short scan session. */
internal class PartialPanTracker(private val options: PartialNumberOptions = PartialNumberOptions(), private val validation: ValidationOptions = ValidationOptions()) {
    private val fragmentPattern = Pattern.compile("(?<![0-9])([0-9][0-9 -]{6,28}[0-9])(?![0-9])")
    private val seen = mutableMapOf<String, Int>()
    private var lastSeenAt = 0L
    private val lastSeen = mutableMapOf<String,Long>()
    var bestFragment: String? = null
        private set

    fun clear() {
        seen.clear()
        lastSeen.clear()
        bestFragment = null
        lastSeenAt = 0
    }

    fun observe(lines: List<CardTextExtractor.Line>, now: Long): String? {
        if (!options.enabled) return null
        if (lastSeenAt != 0L && now - lastSeenAt > options.retentionMs) clear()
        val stale = lastSeen.filterValues { now - it > options.retentionMs }.keys
        stale.forEach { seen.remove(it); lastSeen.remove(it) }
        bestFragment = seen.keys.maxByOrNull { it.length }
        // Joining same-row OCR pieces helps when each printed group is detected separately.
        // A set prevents duplicate rows in one frame from counting as repeated evidence.
        val frameFragments = mutableSetOf<String>()
        for (line in CardTextExtractor.joinedRows(lines)) {
            val matcher = fragmentPattern.matcher(line.text)
            while (matcher.find()) {
                val digits = matcher.group(1)!!.replace(" ", "").replace("-", "")
                if (digits.length in 8..14) frameFragments += digits
            }
        }
        if (frameFragments.isEmpty()) return null
        if ((seen.keys + frameFragments).toSet().size > options.maxFragments) { clear(); return null }
        lastSeenAt = now
        for (fragment in frameFragments) {
            lastSeen[fragment] = now
            seen[fragment] = minOf(options.readsPerFragment, (seen[fragment] ?: 0) + 1)
            if (bestFragment == null || fragment.length > bestFragment!!.length) bestFragment = fragment
        }
        // Every fragment used in a merge must have appeared in two different frames.
        val stable = seen.filterValues { it >= options.readsPerFragment }.keys.toList()
        val completed = mutableSetOf<String>()
        for (i in stable.indices) for (j in i + 1 until stable.size) {
            addMerged(stable[i], stable[j], completed)
            addMerged(stable[j], stable[i], completed)
        }
        // Even a Luhn-valid completion is rejected when there is more than one possibility.
        return completed.singleOrNull()
    }

    private fun addMerged(first: String, second: String, completed: MutableSet<String>) {
        for (overlap in options.minimumOverlap until minOf(first.length, second.length)) {
            if (!first.regionMatches(first.length - overlap, second, 0, overlap)) continue
            val merged = first + second.substring(overlap)
            if (CardTextExtractor.isAcceptedNumber(merged) && cardBrand(merged) in validation.supportedBrands) completed += merged
        }
    }
}
