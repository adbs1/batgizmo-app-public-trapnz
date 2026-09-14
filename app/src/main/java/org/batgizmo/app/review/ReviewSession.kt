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
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.batgizmo.app.WavFileReader
import org.batgizmo.app.trapnz.LocationSummary
import org.batgizmo.app.trapnz.SkipReason
import org.batgizmo.app.trapnz.partitionUploadables
import org.batgizmo.app.trapnz.summariseLocations
import timber.log.Timber

class ReviewSession(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val store = ReviewStore(context)

    private val _items = MutableStateFlow<List<FileReview>>(emptyList())
    val items: StateFlow<List<FileReview>> = _items.asStateFlow()

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _showSummary = MutableStateFlow(false)
    val showSummary: StateFlow<Boolean> = _showSummary.asStateFlow()

    val current: FileReview?
        get() = _items.value.getOrNull(_index.value)

    val hasSession: Boolean
        get() = _items.value.isNotEmpty()

    fun begin(uris: List<Uri>) {
        val stored = store.load()
        val built = uris.map { uri ->
            val key = uri.toString()
            val name = org.batgizmo.app.DocumentHelper.getFileName(context, uri) ?: uri.lastPathSegment ?: key
            takeReadPermission(uri)
            val previous = stored[key]
            previous?.copy(uri = key, fileName = name)
                ?: FileReview(
                    key = key,
                    uri = key,
                    fileName = name,
                    recordedAtEpochMs = GuanoScan.parseRecordedAt(name)
                )
        }
        _items.value = built
        _index.value = if (built.isEmpty()) -1 else 0
        _showSummary.value = false
        scanMissingMetadata(built)
    }

    fun ensureContains(uri: Uri, fileName: String) {
        val key = uri.toString()
        if (_items.value.any { it.key == key }) {
            select(key)
            return
        }
        if (_items.value.isEmpty()) {
            begin(listOf(uri))
            return
        }
        val extra = FileReview(
            key = key,
            uri = key,
            fileName = fileName,
            recordedAtEpochMs = GuanoScan.parseRecordedAt(fileName)
        )
        _items.value = _items.value + extra
        _index.value = _items.value.lastIndex
    }

    fun select(uri: Uri) = select(uri.toString())

    fun select(key: String) {
        val i = _items.value.indexOfFirst { it.key == key }
        if (i >= 0) _index.value = i
    }

    fun enrichFromOpen(uri: Uri, info: WavFileReader.WavFileInfo) {
        val key = uri.toString()
        ensureContains(uri, info.fileName ?: info.fileUri.lastPathSegment ?: key)
        val meta = GuanoScan.fromGuano(info.guanoChunkInfo, info.fileName ?: info.fileUri.lastPathSegment ?: "")
        update(key) { review ->
            review.copy(
                fileName = info.fileName ?: review.fileName,
                latitude = meta.location?.lat ?: review.latitude,
                longitude = meta.location?.lon ?: review.longitude,
                recordedAtEpochMs = meta.recordedAtEpochMs ?: review.recordedAtEpochMs
            )
        }
        select(key)
    }

    /**
     * Apply a human tag to the current file. Does not change which file is current
     * and does not open the summary — the UI decides when to advance or finish.
     */
    fun tagCurrent(flag: ReviewFlag) {
        val item = current ?: return
        val now = System.currentTimeMillis()
        update(item.key) {
            it.copy(
                flag = flag,
                reviewedAtEpochMs = if (flag == ReviewFlag.UNREVIEWED) null else now,
                uploaded = false
            )
        }
    }

    fun showSummary() {
        if (_items.value.isNotEmpty()) _showSummary.value = true
    }

    fun dismissSummary() {
        _showSummary.value = false
    }

    fun clear() {
        _items.value = emptyList()
        _index.value = -1
        _showSummary.value = false
    }

    fun markUploaded(keys: Collection<String>) {
        val set = keys.toSet()
        val updated = _items.value.map { review ->
            if (review.key in set) review.copy(uploaded = true) else review
        }
        _items.value = updated
        store.upsertAll(updated.filter { it.key in set })
    }

    fun counts(): ReviewSummaryCounts {
        val items = _items.value
        val (eligible, skipped) = partitionUploadables(items)
        return ReviewSummaryCounts(
            total = items.size,
            bats = items.count { it.flag == ReviewFlag.BATS },
            noBats = items.count { it.flag == ReviewFlag.NO_BATS },
            skipped = items.count { it.flag == ReviewFlag.SKIPPED },
            unreviewed = items.count { it.flag == ReviewFlag.UNREVIEWED },
            uploadable = eligible.size,
            skippedNoGps = skipped.count { it.reason == SkipReason.NO_GPS },
            skippedNoDate = skipped.count { it.reason == SkipReason.NO_DATE }
        )
    }

    fun summaries(): List<LocationSummary> {
        val (eligible, _) = partitionUploadables(_items.value)
        return summariseLocations(eligible)
    }

    private fun update(key: String, transform: (FileReview) -> FileReview) {
        val next = _items.value.map { if (it.key == key) transform(it) else it }
        _items.value = next
        next.firstOrNull { it.key == key }?.let { store.upsert(it) }
    }

    private fun scanMissingMetadata(built: List<FileReview>) {
        scope.launch(Dispatchers.IO) {
            val scanned = built.map { review ->
                if (review.location != null && review.recordedAtEpochMs != null) review
                else {
                    val meta = GuanoScan.scan(context, Uri.parse(review.uri), review.fileName)
                    review.copy(
                        latitude = review.latitude ?: meta.location?.lat,
                        longitude = review.longitude ?: meta.location?.lon,
                        recordedAtEpochMs = review.recordedAtEpochMs ?: meta.recordedAtEpochMs
                    )
                }
            }
            // Merge metadata only. A wholesale replace would wipe tags applied while
            // this scan was still running.
            val scannedByKey = scanned.associateBy { it.key }
            val merged = _items.value.map { live ->
                val fresh = scannedByKey[live.key] ?: return@map live
                live.copy(
                    latitude = live.latitude ?: fresh.latitude,
                    longitude = live.longitude ?: fresh.longitude,
                    recordedAtEpochMs = live.recordedAtEpochMs ?: fresh.recordedAtEpochMs
                )
            }
            _items.value = merged
            store.upsertAll(merged)
        }
    }

    private fun takeReadPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Timber.d("No persistable read grant for $uri: ${e.message}")
        }
    }
}
