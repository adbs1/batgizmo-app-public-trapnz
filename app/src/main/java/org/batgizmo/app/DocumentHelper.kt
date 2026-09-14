package org.batgizmo.app

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import timber.log.Timber

class DocumentHelper {

    data class UriData(
        val uri: Uri,
        val previousAvailable: Boolean,
        val nextAvailable: Boolean,
        val index: Int = 0,
        val count: Int = 1
    )

    companion object {

        // SAF provider that exposes shared/external storage; used to hint an initial folder.
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        /**
         * MIME types for WAV files used to filter the document picker. SAF derives a file's MIME
         * from its extension using the device's own extension->MIME table, which varies by OEM, so
         * we list every common .wav variant. Otherwise, on devices that map .wav to a type we don't
         * list, the files (and hence the folder containing them) are filtered out of the picker.
         */
        val WAV_MIME_TYPES = arrayOf(
            "audio/wav",
            "audio/x-wav",
            "audio/wave",
            "audio/vnd.wave"
        )

        /**
         * A documents-provider URI pointing at the app's public recordings folder under Documents,
         * suitable as an EXTRA_INITIAL_URI hint for the document picker. The folder need not exist:
         * if it doesn't, the picker silently ignores the hint and opens at its default location.
         */
        private fun appRecordingsFolderUri(): Uri {
            val documentId =
                "primary:${Environment.DIRECTORY_DOCUMENTS}/${FileWriter.PUBLIC_FOLDER_NAME}"
            return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)
        }

        /**
         * Get the display name for a file given its Uri.
         */
        fun getFileName(context: Context, uri: Uri): String? {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            var fileName: String? = null

            cursor?.use {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && it.moveToFirst()) {
                    fileName = it.getString(nameIndex)  // Extract filename
                }
            }

            return fileName
        }

        /**
         * Create a document picker that the UI can use to have the user select
         * one or more documents.
         */
        @Composable
        fun composeDocumentPickerLauncher(
            documentHelper: DocumentHelper,
            onSelection: (UriData) -> Unit
        )
            : ManagedActivityResultLauncher<Array<String>, List<Uri>> {

            val context = LocalContext.current

            /*
             * We use OpenMultipleDocuments rather than the folder picker, because the folder picker
             * doesn't work on MyDrive or USB drives. OpenMultipleDocuments allows them to
             * be browsed, and as a "select all" feature that does the job of opening
             * the entire folder.
             */
            // Hint the picker to open at the app's recordings folder, but only the first time it
            // is launched in this app run; subsequent launches let the picker reopen wherever the
            // user last browsed:
            val initialUri = remember { appRecordingsFolderUri() }

            val launcher = rememberLauncherForActivityResult(
                // We use OpenDocument rather then GetContent is it allows multiple
                // MIME types:
                contract = InitialFolderOpenDocuments(documentHelper, initialUri)
            ) { list: List<Uri> ->

                // Timber.d("rememberLauncherForActivityResult callback invoked: list.size = ${list.size}")

                val initialUriData = documentHelper.initDocumentState(context, list)

                initialUriData?.let { it ->
                    val filename = getFileName(context, it.uri)
                    Timber.i("Selected file: $filename (${it.index + 1}/${it.count})")

                    // The following callback calls resetUIMode():
                    onSelection(it)
                }
            }

            return launcher
        }

        /**
         * An OpenMultipleDocuments contract that hints the picker to start at [initialUri] the
         * first time it is launched in this app run (see [consumeInitialFolderHint]).
         */
        private class InitialFolderOpenDocuments(
            private val documentHelper: DocumentHelper,
            private val initialUri: Uri
        ) : ActivityResultContracts.OpenMultipleDocuments() {
            override fun createIntent(context: Context, input: Array<String>): Intent {
                val intent = super.createIntent(context, input)
                if (documentHelper.consumeInitialFolderHint()) {
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                }
                return intent
            }
        }

        private val chunk = Regex("\\d+|\\D+")

        fun naturalCompare(a: String, b: String): Int {
            val aParts = chunk.findAll(a).map { it.value }.toList()
            val bParts = chunk.findAll(b).map { it.value }.toList()
            val n = minOf(aParts.size, bParts.size)
            for (i in 0 until n) {
                val ap = aParts[i]
                val bp = bParts[i]
                val cmp = if (ap[0].isDigit() && bp[0].isDigit()) {
                    ap.toBigInteger().compareTo(bp.toBigInteger())
                } else {
                    ap.compareTo(bp, ignoreCase = true)
                }
                if (cmp != 0) return cmp
            }
            return aParts.size.compareTo(bParts.size)
        }

        /**
         * Parent folder as a MediaStore RELATIVE_PATH (trailing slash) from a SAF document id,
         * e.g. `primary:Documents/BatGizmo/2026-09-08/20260908_210000.wav`
         * → `Documents/BatGizmo/2026-09-08/`.
         */
        fun relativePathFromDocumentId(documentId: String): String? {
            val afterColon = documentId.substringAfter(':', missingDelimiterValue = "")
            if (afterColon.isBlank()) return null
            if (documentId.startsWith("raw:", ignoreCase = true)) {
                val documentsAt = afterColon.indexOf("/Documents/")
                if (documentsAt < 0) return null
                val fromDocuments = afterColon.substring(documentsAt + 1)
                val parent = fromDocuments.substringBeforeLast('/', missingDelimiterValue = "")
                return if (parent.isBlank()) null else "$parent/"
            }
            val parent = afterColon.substringBeforeLast('/', missingDelimiterValue = "")
            if (parent.isBlank() || parent == afterColon) return null
            return "$parent/"
        }

        private fun isWavName(name: String?): Boolean =
            name?.endsWith(".wav", ignoreCase = true) == true
    }

    private var uriList: List<Uri>? = null
    private var currentIndex: Int? = null

    // True until the document picker has been launched once in this app run, so we only steer it
    // to the app folder on first use and respect the picker's own memory afterwards:
    private var initialFolderHintPending = true

    /** Returns true at most once per app run, the first time the document picker is launched. */
    private fun consumeInitialFolderHint(): Boolean {
        if (!initialFolderHintPending)
            return false
        initialFolderHintPending = false
        return true
    }

    init {
        reset()
    }

    /**
     * Initialise based on the list of Uris provided,
     */
    private fun initDocumentState(context: Context, theList: List<Uri>): UriData? {
        reset()

        val expanded = expandToFolderSiblings(context, theList)
        val sorted = expanded.sortedWith { a, b ->
            naturalCompare(
                getFileName(context, a) ?: a.toString(),
                getFileName(context, b) ?: b.toString()
            )
        }
        uriList = sorted

        if (sorted.isEmpty()) return null

        val startName = theList.firstOrNull()?.let { getFileName(context, it) }
        val start = sorted.indexOfFirst { uri ->
            uri == theList.first() ||
                uri.toString() == theList.first().toString() ||
                (startName != null && getFileName(context, uri) == startName)
        }.takeIf { it >= 0 } ?: 0
        currentIndex = start
        Timber.i("Review list has ${sorted.size} file(s); starting at ${start + 1}")
        return uriDataAt(start)
    }

    fun allUris(): List<Uri> = uriList ?: emptyList()

    fun hasNext(): Boolean {
        val i = currentIndex ?: return false
        val list = uriList ?: return false
        return i < list.size - 1
    }

    fun reset() {
        uriList = null
        currentIndex = null
    }

    fun getPreviousFile(): UriData? {
        currentIndex?.let { i ->
            uriList?.let { list ->
                if (i > 0) {
                    val previous = i - 1
                    currentIndex = previous
                    return uriDataAt(previous)
                }
            }
        }
        return null
    }

    fun getNextFile(): UriData? {
        currentIndex?.let { i ->
            uriList?.let { list ->
                if (i < list.size - 1) {
                    val next = i + 1
                    currentIndex = next
                    return uriDataAt(next)
                }
            }
        }
        return null
    }

    fun select(uri: Uri): UriData? {
        val list = uriList ?: return null
        val i = list.indexOfFirst { it == uri || it.toString() == uri.toString() }
        if (i < 0) return null
        currentIndex = i
        return uriDataAt(i)
    }

    private fun uriDataAt(i: Int): UriData? {
        val list = uriList ?: return null
        if (i !in list.indices) return null
        return UriData(list[i], i > 0, i < list.size - 1, i, list.size)
    }

    /**
     * A single tap in the document picker only grants that one WAV. If it lives in a
     * MediaStore folder the app can see (typical for BatGizmo night folders), include
     * the sibling WAVs so Tag Bat can walk the night. Multi-select is left as-is.
     */
    private fun expandToFolderSiblings(context: Context, picked: List<Uri>): List<Uri> {
        if (picked.size != 1) return picked
        val pickedUri = picked[0]
        val relativePath = resolveRelativePath(context, pickedUri) ?: return picked
        val siblings = listWavsInRelativePath(context, relativePath)
        if (siblings.size <= 1) return picked

        val pickedName = getFileName(context, pickedUri)
        val byName = LinkedHashMap<String, Uri>()
        for (uri in siblings) {
            val name = getFileName(context, uri) ?: uri.lastPathSegment ?: continue
            byName[name] = uri
        }
        if (pickedName != null) byName[pickedName] = pickedUri
        Timber.i("Expanded single pick in $relativePath to ${byName.size} WAV(s)")
        return byName.values.toList()
    }

    private fun resolveRelativePath(context: Context, uri: Uri): String? {
        try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Files.FileColumns.RELATIVE_PATH),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                    if (idx >= 0) {
                        val path = cursor.getString(idx)
                        if (!path.isNullOrBlank()) return normalizeRelativePath(path)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.d("RELATIVE_PATH query failed for $uri: ${e.message}")
        }
        if (DocumentsContract.isDocumentUri(context, uri)) {
            try {
                relativePathFromDocumentId(DocumentsContract.getDocumentId(uri))?.let { return it }
            } catch (e: Exception) {
                Timber.d("Document id parse failed for $uri: ${e.message}")
            }
        }
        return null
    }

    private fun listWavsInRelativePath(context: Context, relativePath: String): List<Uri> {
        val volumes = try {
            MediaStore.getExternalVolumeNames(context)
        } catch (_: Exception) {
            setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val out = ArrayList<Uri>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?"
        val args = arrayOf(relativePath)
        for (volume in volumes) {
            try {
                val collection = MediaStore.Files.getContentUri(volume)
                context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                        val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                        val wav = isWavName(name) || (mime != null && mime.contains("wav", ignoreCase = true))
                        if (!wav) continue
                        out.add(ContentUris.withAppendedId(collection, cursor.getLong(idIdx)))
                    }
                }
            } catch (e: Exception) {
                Timber.d("MediaStore list failed for $volume $relativePath: ${e.message}")
            }
        }
        return out
    }

    private fun normalizeRelativePath(path: String): String =
        if (path.endsWith('/')) path else "$path/"
}
