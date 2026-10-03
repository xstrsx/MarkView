package com.markview.viewer

import androidx.core.net.toUri
import android.net.Uri

/**
 * Everything MarkView knows about one opened document.
 *
 * [ancestorDocumentIds] is the SAF document-id chain from the authorised tree root
 * (inclusive) down to the directory that contains [uri] (inclusive). It is empty
 * when the parent directory could not be established, in which case the body still
 * renders and relative resources simply report "missing" (project book §5.3).
 */
class DocumentContext(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val fromAuthorizedTree: Boolean,
    val treeUri: Uri?,
    val ancestorDocumentIds: List<String>,
) {

    val registry: ResourceRegistry = ResourceRegistry()

    /** Document id of the directory containing this document, if known. */
    val parentDocumentId: String? get() = ancestorDocumentIds.lastOrNull()

    /** True when relative references can be resolved inside an authorised tree. */
    val canResolveRelative: Boolean get() = treeUri != null && ancestorDocumentIds.isNotEmpty()

    fun snapshot(): DocumentSnapshot = DocumentSnapshot(
        uri = uri.toString(),
        displayName = displayName,
        mimeType = mimeType,
        fromAuthorizedTree = fromAuthorizedTree,
        treeUri = treeUri?.toString(),
        ancestorDocumentIds = ArrayList(ancestorDocumentIds),
    )
}

/** Plain-String form of a [DocumentContext], safe to put into a saved instance state. */
class DocumentSnapshot(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val fromAuthorizedTree: Boolean,
    val treeUri: String?,
    val ancestorDocumentIds: List<String>,
) {
    fun toContext(): DocumentContext? {
        val parsed = uri.toUri()
        return DocumentContext(
            uri = parsed,
            displayName = displayName,
            mimeType = mimeType,
            fromAuthorizedTree = fromAuthorizedTree,
            treeUri = treeUri?.toUri(),
            ancestorDocumentIds = ancestorDocumentIds,
        )
    }
}

/**
 * The native document stack behind the system back button (project book §8).
 *
 * Documents opened from relative Markdown links are pushed here; back pops one
 * document at a time and only leaves the viewer once the stack is empty.
 */
class DocumentStack {

    private val entries = ArrayList<DocumentContext>()

    var currentIndex: Int = -1
        private set

    val size: Int get() = entries.size

    val current: DocumentContext? get() = entries.getOrNull(currentIndex)

    val canGoBack: Boolean get() = currentIndex > 0

    /** Pushes [context], discarding any forward entries left over from a previous back. */
    fun push(context: DocumentContext) {
        while (entries.size > currentIndex + 1) {
            entries.removeAt(entries.size - 1)
        }
        entries.add(context)
        currentIndex = entries.size - 1
    }

    /** Drops the top document and returns the one that becomes visible, or `null` when empty. */
    fun pop(): DocumentContext? {
        if (currentIndex <= 0) return null
        entries.removeAt(entries.size - 1)
        currentIndex = entries.size - 1
        return entries[currentIndex]
    }

    fun clear() {
        entries.clear()
        currentIndex = -1
    }

    fun snapshots(): ArrayList<DocumentSnapshot> =
        ArrayList(entries.map { it.snapshot() })

    fun restore(snapshots: List<DocumentSnapshot>, index: Int) {
        entries.clear()
        for (snapshot in snapshots) {
            snapshot.toContext()?.let { entries.add(it) }
        }
        currentIndex = index.coerceIn(-1, entries.size - 1)
    }
}
