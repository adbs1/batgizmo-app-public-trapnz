/*
 * Copyright (c) 2025-2026 John Mears
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.batgizmo.app.trapnz

import org.batgizmo.app.review.FileReview
import org.batgizmo.app.review.GeoPosition
import org.batgizmo.app.review.ReviewFlag
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos

enum class SkipReason { NO_GPS, NO_DATE, NO_SPECIES }

const val LOCATION_GRID_METERS = 50
private const val METERS_PER_DEG_LAT = 111_320.0

data class EligibleRecord(
    val item: FileReview,
    val dateEpochMs: Long,
    val speciesNames: List<String>
)

data class SkippedRecord(
    val item: FileReview,
    val reason: SkipReason
)

data class LocationSummary(
    val gridKey: String,
    val count: Int,
    val startEpochMs: Long,
    val finishEpochMs: Long,
    val location: GeoPosition,
    val speciesNames: List<String>,
    val itemKeys: List<String>
)

fun observationDateMs(item: FileReview): Long? =
    item.recordedAtEpochMs ?: item.reviewedAtEpochMs

fun partitionUploadables(items: List<FileReview>): Pair<List<EligibleRecord>, List<SkippedRecord>> {
    val eligible = mutableListOf<EligibleRecord>()
    val skipped = mutableListOf<SkippedRecord>()
    for (item in items) {
        if (item.flag != ReviewFlag.BATS) continue
        if (item.uploaded) continue
        if (item.location == null) {
            skipped.add(SkippedRecord(item, SkipReason.NO_GPS))
            continue
        }
        val date = observationDateMs(item)
        if (date == null) {
            skipped.add(SkippedRecord(item, SkipReason.NO_DATE))
            continue
        }
        eligible.add(EligibleRecord(item, date, listOf("Bat")))
    }
    return eligible to skipped
}

fun snapToGrid(lat: Double, lon: Double, meters: Int = LOCATION_GRID_METERS): Triple<String, Double, Double> {
    val latCell = Math.round((lat * METERS_PER_DEG_LAT) / meters)
    val latCenter = (latCell * meters) / METERS_PER_DEG_LAT
    val mPerDegLon = METERS_PER_DEG_LAT * cos(Math.toRadians(latCenter))
    val lonCell = if (mPerDegLon == 0.0) 0L else Math.round((lon * mPerDegLon) / meters)
    val lonCenter = if (mPerDegLon == 0.0) lon else (lonCell * meters) / mPerDegLon
    return Triple("$latCell:$lonCell", latCenter, lonCenter)
}

fun summariseLocations(records: List<EligibleRecord>): List<LocationSummary> {
    val groups = linkedMapOf<String, MutableList<EligibleRecord>>()
    for (record in records) {
        val loc = record.item.location ?: continue
        val (key, _, _) = snapToGrid(loc.lat, loc.lon)
        groups.getOrPut(key) { mutableListOf() }.add(record)
    }
    return groups.map { (key, list) ->
        val loc = list.first().item.location!!
        val snapped = snapToGrid(loc.lat, loc.lon)
        val dates = list.map { it.dateEpochMs }
        LocationSummary(
            gridKey = key,
            count = list.size,
            startEpochMs = dates.min(),
            finishEpochMs = dates.max(),
            location = GeoPosition(snapped.second, snapped.third),
            speciesNames = list.flatMap { it.speciesNames }.distinct(),
            itemKeys = list.map { it.item.key }
        )
    }.sortedBy { it.gridKey }
}

fun normalizeTermName(name: String?): String =
    (name ?: "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

private val BAT_ALIASES = listOf("bat", "bats", "pekapeka")
private val TYPE_ALIASES = listOf("heard", "bat monitor", "acoustic", "call", "calls", "monitor")
private val BATS_DETECTED_ALIASES = listOf("bats detected", "bat detected")

fun matchTerm(terms: List<TrapTerm>, name: String): TrapTerm? {
    if (terms.isEmpty()) return null
    val aliases = if (normalizeTermName(name) in BAT_ALIASES) BAT_ALIASES else listOf(normalizeTermName(name))
    var best: TrapTerm? = null
    var bestScore = 0
    for (term in terms) {
        val n = normalizeTermName(term.name)
        if (n.isEmpty() || n == "other" || n.startsWith("other ")) continue
        val score = when {
            n == aliases.first() -> 100
            n in aliases -> 90
            aliases.any { wordMatch(n, it) } -> 50
            else -> 0
        }
        if (score > bestScore) {
            best = term
            bestScore = score
        }
    }
    return best
}

fun matchSpecies(terms: List<TrapTerm>, names: List<String>): List<TrapTerm> {
    val found = mutableListOf<TrapTerm>()
    val seen = mutableSetOf<Int>()
    for (name in names) {
        val term = matchTerm(terms, name) ?: continue
        if (seen.add(term.tid)) found.add(term)
    }
    if (found.isEmpty() && names.any { it != "Rat" }) {
        matchTerm(terms, "Bat")?.let { found.add(it) }
    }
    return found
}

fun findHeardType(terms: List<TrapTerm>): TrapTerm? {
    for (alias in TYPE_ALIASES) {
        for (term in terms) {
            val n = normalizeTermName(term.name)
            if (n == alias || n.contains(alias)) return term
        }
    }
    return null
}

fun findSurveyEvent(terms: List<TrapTerm>, name: String = "Bats Detected"): TrapTerm? {
    if (terms.isEmpty()) return null
    val aliases = listOf(normalizeTermName(name)) + BATS_DETECTED_ALIASES
    var best: TrapTerm? = null
    var bestScore = 0
    for (term in terms) {
        val n = normalizeTermName(term.name)
        if (n.isEmpty() || n.startsWith("no ") || n.contains(" not ") ||
            n.contains("none") || n.contains("absent")
        ) continue
        val score = when {
            n == aliases.first() -> 100
            n in aliases -> 90
            aliases.any { n.contains(it) } -> 50
            else -> 0
        }
        if (score > bestScore) {
            best = term
            bestScore = score
        }
    }
    return best
}

fun mergeTerms(vararg lists: List<TrapTerm>): List<TrapTerm> {
    val out = mutableListOf<TrapTerm>()
    val seen = mutableSetOf<Int>()
    for (list in lists) {
        for (term in list) {
            if (seen.add(term.tid)) out.add(term)
        }
    }
    return out
}

fun observationNotes(summary: LocationSummary, speciesLabel: String): String {
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
    val lines = mutableListOf(
        "${summary.count} bat recording${if (summary.count == 1) "" else "s"}",
        "Start: ${fmt.format(Instant.ofEpochMilli(summary.startEpochMs))}",
        "Finish: ${fmt.format(Instant.ofEpochMilli(summary.finishEpochMs))}"
    )
    if (speciesLabel.isNotBlank()) lines.add("species: $speciesLabel")
    lines.add("Unless BatGizmo")
    return lines.joinToString("\n")
}

private fun wordMatch(haystack: String, needle: String): Boolean =
    Regex("""(?:^| )${Regex.escape(needle)}(?:$| )""").containsMatchIn(haystack)
