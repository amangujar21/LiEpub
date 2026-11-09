package com.storyweaver.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.storyweaver.epub.FilenameSanitizer

/**
 * Storage Access Framework helpers for the default output folder and collision-safe writes.
 */
object SafStorage {
    fun takePersistableReadWrite(context: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
    }

    /**
     * Writes [bytes] under [treeUri] as [preferredFilename], never overwriting silently.
     * Returns the created document URI string, or null on failure.
     */
    fun writeEpub(
        context: Context,
        treeUri: Uri,
        preferredFilename: String,
        bytes: ByteArray,
    ): String? = writeDocument(
        context = context,
        treeUri = treeUri,
        safeFilename = FilenameSanitizer.sanitize(preferredFilename),
        mimeType = "application/epub+zip",
        bytes = bytes,
    )

    /** Same as [writeEpub] for a Markdown export. */
    fun writeMarkdown(
        context: Context,
        treeUri: Uri,
        preferredFilename: String,
        bytes: ByteArray,
    ): String? = writeDocument(
        context = context,
        treeUri = treeUri,
        safeFilename = FilenameSanitizer.sanitize(preferredFilename.removeSuffix(".md"))
            .removeSuffix(".epub") + ".md",
        mimeType = "text/markdown",
        bytes = bytes,
    )

    private fun writeDocument(
        context: Context,
        treeUri: Uri,
        safeFilename: String,
        mimeType: String,
        bytes: ByteArray,
    ): String? {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!tree.canWrite()) return null
        val extension = "." + safeFilename.substringAfterLast('.')
        val stem = safeFilename.removeSuffix(extension)
        var name = safeFilename
        var index = 1
        while (tree.findFile(name) != null) {
            name = "$stem (${index++})$extension"
            if (index > 200) return null
        }
        val doc = tree.createFile(mimeType, name.removeSuffix(extension))
            ?: tree.createFile(mimeType, name)
            ?: return null
        // Providers append the extension for the MIME type themselves; ensure content is written.
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: return null
        return doc.uri.toString()
    }

    fun displayName(context: Context, treeUriString: String?): String {
        if (treeUriString.isNullOrBlank()) return "Not set"
        val uri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return "Not set"
        val tree = DocumentFile.fromTreeUri(context, uri)
        return tree?.name ?: uri.lastPathSegment ?: "Selected folder"
    }
}
