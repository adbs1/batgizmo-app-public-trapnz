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
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

class ReviewStore(context: Context) {
    private val file = File(context.filesDir, "file-reviews.json")

    fun load(): Map<String, FileReview> {
        if (!file.isFile) return emptyMap()
        return try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("files") ?: JSONArray()
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val review = fromJson(o) ?: continue
                    put(review.key, review)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read review store")
            emptyMap()
        }
    }

    @Synchronized
    fun upsert(review: FileReview) {
        val all = load().toMutableMap()
        all[review.key] = review
        save(all.values)
    }

    @Synchronized
    fun upsertAll(reviews: Collection<FileReview>) {
        val all = load().toMutableMap()
        for (review in reviews) all[review.key] = review
        save(all.values)
    }

    private fun save(reviews: Collection<FileReview>) {
        try {
            val arr = JSONArray()
            for (review in reviews) arr.put(toJson(review))
            file.writeText(JSONObject().put("files", arr).toString())
        } catch (e: Exception) {
            Timber.w(e, "Failed to write review store")
        }
    }

    private fun toJson(review: FileReview): JSONObject =
        JSONObject()
            .put("key", review.key)
            .put("uri", review.uri)
            .put("fileName", review.fileName)
            .put("flag", review.flag.name)
            .put("latitude", review.latitude)
            .put("longitude", review.longitude)
            .put("recordedAtEpochMs", review.recordedAtEpochMs)
            .put("reviewedAtEpochMs", review.reviewedAtEpochMs)
            .put("uploaded", review.uploaded)

    private fun fromJson(o: JSONObject): FileReview? {
        val key = o.optString("key").ifBlank { return null }
        val flag = try {
            ReviewFlag.valueOf(o.optString("flag", ReviewFlag.UNREVIEWED.name))
        } catch (_: Exception) {
            ReviewFlag.UNREVIEWED
        }
        return FileReview(
            key = key,
            uri = o.optString("uri", key),
            fileName = o.optString("fileName", key),
            flag = flag,
            latitude = o.optDoubleOrNull("latitude"),
            longitude = o.optDoubleOrNull("longitude"),
            recordedAtEpochMs = o.optLongOrNull("recordedAtEpochMs"),
            reviewedAtEpochMs = o.optLongOrNull("reviewedAtEpochMs"),
            uploaded = o.optBoolean("uploaded", false)
        )
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name) else null

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null
}
