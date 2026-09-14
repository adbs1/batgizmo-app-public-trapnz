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

package org.batgizmo.app.review

import android.content.Context
import android.net.Uri
import org.batgizmo.app.WavFileParser
import timber.log.Timber
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class WavReviewMeta(
    val location: GeoPosition?,
    val recordedAtEpochMs: Long?
)

object GuanoScan {

    private val fileStamp = Regex(
        """(?:^|[^0-9])(\d{4})(\d{2})(\d{2})[_T-]?(\d{2})(\d{2})(\d{2})(?:$|[^0-9])"""
    )

    fun parseRecordedAt(name: String): Long? {
        val stem = name.substringBeforeLast('.')
        val stamp = fileStamp.find(stem)
        if (stamp != null) {
            val (year, month, day, hour, minute, second) = stamp.destructured
            val y = year.toInt()
            val mo = month.toInt()
            val d = day.toInt()
            val h = hour.toInt()
            val mi = minute.toInt()
            val s = second.toInt()
            val valid = mo in 1..12 && d in 1..31 && h < 24 && mi < 60 && s < 60
            if (valid) {
                val ldt = LocalDateTime.of(y, mo, d, h, mi, s)
                if (ldt.monthValue == mo && ldt.dayOfMonth == d) {
                    return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            }
        }
        if (Regex("^[0-9A-Fa-f]{8}$").matches(stem)) {
            val ms = stem.toLong(16) * 1000L
            val year = java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.YEAR)
            if (year > 2000) return ms
        }
        return null
    }

    fun parseLocPosition(value: String?): GeoPosition? {
        if (value.isNullOrBlank()) return null
        val parts = value.trim().split(Regex("""[\s,]+""")).mapNotNull { it.toDoubleOrNull() }
        if (parts.size < 2) return null
        val lat = parts[0]
        val lon = parts[1]
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPosition(lat, lon)
    }

    fun parseGuanoTimestamp(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        try {
            return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        val localFormats = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        )
        for (fmt in localFormats) {
            try {
                return LocalDateTime.parse(trimmed, fmt)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    fun fromGuano(guano: WavFileParser.GuanoChunkInfo?, fileName: String): WavReviewMeta {
        val loc = parseLocPosition(guano?.entriesMap?.get("loc position")?.value)
        val recorded = parseRecordedAt(fileName)
            ?: parseGuanoTimestamp(guano?.entriesMap?.get("timestamp")?.value)
        return WavReviewMeta(loc, recorded)
    }

    fun scan(context: Context, uri: Uri, fileName: String): WavReviewMeta {
        val fromName = parseRecordedAt(fileName)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val guano = readGuanoFromStream(input)
                fromGuano(guano, fileName).let {
                    WavReviewMeta(it.location, it.recordedAtEpochMs ?: fromName)
                }
            } ?: WavReviewMeta(null, fromName)
        } catch (e: Exception) {
            Timber.w(e, "GUANO scan failed for $fileName")
            WavReviewMeta(null, fromName)
        }
    }

    private fun readGuanoFromStream(input: InputStream): WavFileParser.GuanoChunkInfo? {
        val riff = readExact(input, 12) ?: return null
        if (ascii(riff, 0, 4) != "RIFF" || ascii(riff, 8, 4) != "WAVE") return null
        var guano: WavFileParser.GuanoChunkInfo? = null
        while (true) {
            val header = readExact(input, 8) ?: break
            val id = ascii(header, 0, 4)
            val size = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val payload = size.toLong().coerceAtLeast(0L)
            val pad = if (payload % 2L == 1L) 1L else 0L
            if (id == "guan") {
                val bytes = readExact(input, size) ?: break
                guano = parseGuanoBytes(bytes)
                skipFully(input, pad)
            } else {
                skipFully(input, payload + pad)
            }
        }
        return guano
    }

    private fun parseGuanoBytes(bytes: ByteArray): WavFileParser.GuanoChunkInfo {
        val text = String(bytes, Charsets.UTF_8)
        val entriesList = mutableListOf<WavFileParser.GuanoEntry>()
        val entriesMap = mutableMapOf<String, WavFileParser.GuanoEntry>()
        for (line in text.split("\r\n", "\n")) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (key.isEmpty()) continue
            val entry = WavFileParser.GuanoEntry(key, value)
            entriesList.add(entry)
            entriesMap[key.lowercase()] = entry
        }
        return WavFileParser.GuanoChunkInfo(text, entriesList, entriesMap)
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun readExact(input: InputStream, count: Int): ByteArray? {
        if (count <= 0) return ByteArray(0)
        val out = ByteArray(count)
        var got = 0
        while (got < count) {
            val n = input.read(out, got, count - got)
            if (n < 0) return if (got == 0) null else throw EOFException()
            got += n
        }
        return out
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            if (input.read() < 0) break
            left--
        }
    }
}
