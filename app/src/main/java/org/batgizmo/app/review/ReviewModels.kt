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

enum class ReviewFlag {
    UNREVIEWED,
    BATS,
    NO_BATS,
    SKIPPED;

    val label: String
        get() = when (this) {
            UNREVIEWED -> "Untagged"
            BATS -> "Bat"
            NO_BATS -> "Not a bat"
            SKIPPED -> "Skipped"
        }
}

data class GeoPosition(val lat: Double, val lon: Double)

data class FileReview(
    val key: String,
    val uri: String,
    val fileName: String,
    val flag: ReviewFlag = ReviewFlag.UNREVIEWED,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val recordedAtEpochMs: Long? = null,
    val reviewedAtEpochMs: Long? = null,
    val uploaded: Boolean = false
) {
    val location: GeoPosition?
        get() {
            val lat = latitude
            val lon = longitude
            return if (lat != null && lon != null) GeoPosition(lat, lon) else null
        }
}

data class ReviewSummaryCounts(
    val total: Int,
    val bats: Int,
    val noBats: Int,
    val skipped: Int,
    val unreviewed: Int,
    val uploadable: Int,
    val skippedNoGps: Int,
    val skippedNoDate: Int
)
