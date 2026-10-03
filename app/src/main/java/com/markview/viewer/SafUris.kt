package com.markview.viewer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Human-readable name / location helpers for authorised tree URIs.
 *
 * The location string is derived from the provider's tree document id, so it never
 * needs a real file-system path (project book §4.1: "无法读取位置时显示位置不可用").
 */
object SafUris {

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private const val DOWNLOADS = "com.android.providers.downloads.documents"

    /** Folder name as reported by the provider, falling back to the tree document id. */
    fun folderName(context: Context, treeUri: Uri): String? {
        val fromProvider = runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull()
        if (!fromProvider.isNullOrBlank()) return fromProvider
        val documentId = treeDocumentId(treeUri) ?: return null
        val tail = documentId.substringAfter(':', documentId).substringAfterLast('/')
        return tail.takeIf { it.isNotBlank() } ?: documentId
    }

    /** Readable location label, or `null` when it cannot be described. */
    fun locationLabel(treeUri: Uri): String? {
        val authority = treeUri.authority?.takeIf { it.isNotBlank() } ?: return null
        val documentId = treeDocumentId(treeUri)
        return when {
            authority == EXTERNAL_STORAGE && documentId != null -> {
                val volume = documentId.substringBefore(':', "")
                val path = documentId.substringAfter(':', "")
                val root = when {
                    volume.isBlank() -> "存储"
                    volume.equals("primary", ignoreCase = true) -> "内部存储"
                    else -> "SD 卡 ($volume)"
                }
                if (path.isBlank()) root else "$root/$path"
            }
            authority == DOWNLOADS -> "下载"
            documentId != null -> {
                val tail = documentId.substringAfter(':', documentId).substringAfterLast('/')
                if (tail.isNotBlank()) "$authority/$tail" else authority
            }
            else -> authority
        }
    }

    private fun treeDocumentId(treeUri: Uri): String? =
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
}
