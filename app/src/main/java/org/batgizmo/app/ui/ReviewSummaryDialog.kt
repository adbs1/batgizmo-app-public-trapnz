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

package org.batgizmo.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.batgizmo.app.review.ReviewSummaryCounts

@Composable
fun ReviewSummaryDialog(
    counts: ReviewSummaryCounts,
    onDismiss: () -> Unit,
    onUpload: () -> Unit
) {
    val lines = buildList {
        add("${counts.total} file${if (counts.total == 1) "" else "s"} in this review")
        add("${counts.bats} tagged bat")
        add("${counts.noBats} not a bat")
        add("${counts.skipped} skipped")
        if (counts.unreviewed > 0) add("${counts.unreviewed} still untagged")
        add("${counts.uploadable} ready for Trap.NZ")
        if (counts.skippedNoGps > 0) add("${counts.skippedNoGps} bat tag${if (counts.skippedNoGps == 1) "" else "s"} skipped (no GPS)")
        if (counts.skippedNoDate > 0) add("${counts.skippedNoDate} bat tag${if (counts.skippedNoDate == 1) "" else "s"} skipped (no date)")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review results") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(lines.joinToString("\n"))
                if (counts.uploadable > 0) {
                    Spacer(Modifier.height(12.dp))
                    Text("Upload tagged bat recordings to Trap.NZ? WAV files stay on the phone.")
                }
            }
        },
        confirmButton = {
            if (counts.uploadable > 0) {
                TextButton(onClick = onUpload) { Text("Upload to Trap.NZ") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = {
            if (counts.uploadable > 0) {
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    )
}
