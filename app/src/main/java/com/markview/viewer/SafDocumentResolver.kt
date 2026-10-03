package com.markview.viewer

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.abs

/**
 * All Storage Access Framework work: turning an incoming `content://` URI into a
 * [DocumentContext], and resolving relative references inside the authorised tree.
 *
 * Design notes (project book §5.2, §5.3, MV-01):
 *  - `content://` URIs are never converted to file paths and `file://` is never used;
 *  - a document is tied to an authorised tree only when the provider authority matches
 *    and one of these holds: same document id, a hierarchical id below the tree root,
 *    an exact match found by bounded traversal, or a unique content-identity match
 *    (display name + size, plus last-modified when both sides report it);
 *  - matching by file name alone is never done, and two candidates are treated as
 *    ambiguous rather than guessed;
 *  - when both a folder and one of its parents are authorised, the outermost matching tree
 *    wins (see [TreePathPolicy.preferShallower]), so the resolution base no longer depends on
 *    the order the system returns persisted grants in;
 *  - relative references walk that directory chain by *name*, so no provider-specific
 *    path format is assumed, and anything stepping above the tree root returns `null`;
 *  - every decision is written to logcat (identifiers only, never document content) so
 *    that the next real-device round can be diagnosed from a log.
 */
class SafDocumentResolver(context: Context) {

    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver

    private val listCache: MutableMap<String, List<ChildEntry>> =
        Collections.synchronizedMap(object : LinkedHashMap<String, List<ChildEntry>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ChildEntry>>?): Boolean =
                size > LIST_CACHE_ENTRIES
        })

    private val associationCache = Collections.synchronizedMap(HashMap<String, TreeAssociation>())
    private val noAssociationCache = Collections.synchronizedSet(HashSet<String>())

    class ChildEntry(
        val documentId: String,
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    class ResourceTarget(val uri: Uri, val mimeType: String)

    /** How a document was tied to an authorised tree; logged so a report is actionable. */
    private enum class MatchKind {
        NONE,
        DOCUMENT_ID,
        DERIVED_PATH,
        DOCUMENT_ID_SEARCH,
        URI_SEARCH,
        CONTENT_IDENTITY,
        /** The same file, proven by content, delivered by a *different* provider (MV-14). */
        CONTENT_IDENTITY_CROSS_PROVIDER,
    }

    private class TreeAssociation(
        val treeUri: Uri,
        val ancestorDocumentIds: List<String>,
        val kind: MatchKind,
    )

    private class DocumentMetadata(val name: String?, val size: Long?, val lastModified: Long?) {
        /** Identity matching needs a name and a size; without them it is not attempted. */
        val usableForIdentity: Boolean get() = !name.isNullOrBlank() && size != null && size >= 0
    }

    private class SearchOutcome(
        val exactChain: List<String>?,
        val exactKind: MatchKind,
        val identityChains: List<List<String>>,
    )

    /** Drops cached listings and tree associations; called when the visible document changes. */
    fun invalidateCache() {
        listCache.clear()
        associationCache.clear()
        noAssociationCache.clear()
    }

    // ------------------------------------------------------------------ resolve

    /**
     * Builds the context for a document URI. Returns `null` only when the URI is
     * unusable; a missing parent directory is not a failure.
     */
    suspend fun resolve(
        uri: Uri,
        declaredMimeType: String? = null,
        declaredName: String? = null,
    ): DocumentContext? = withContext(Dispatchers.IO) {
        if (!isReadable(uri)) {
            log("resolve: unreadable uri=$uri")
            return@withContext null
        }
        val name = declaredName?.takeIf { it.isNotBlank() }
            ?: queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: FALLBACK_NAME
        val declared = declaredMimeType ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        val mime = MimeTypes.resolve(name, declared)
        val association = findTreeAssociation(uri)
        log(
            "resolve: uri=$uri name=$name mime=$mime" +
                " authority=${uri.authority} documentId=${documentIdOf(uri)}" +
                " tree=${association?.treeUri}" +
                " match=${association?.kind ?: MatchKind.NONE}" +
                " chainDepth=${association?.ancestorDocumentIds?.size ?: 0}"
        )
        DocumentContext(
            uri = uri,
            displayName = name,
            mimeType = mime,
            fromAuthorizedTree = association != null,
            treeUri = association?.treeUri,
            ancestorDocumentIds = association?.ancestorDocumentIds ?: emptyList(),
        )
    }

    /** Document id of a document URI, or `null` when the URI is not a document URI. */
    fun documentIdOf(uri: Uri): String? = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()

    private fun isReadable(uri: Uri): Boolean = runCatching {
        contentResolver.openInputStream(uri)?.use { true } ?: false
    }.getOrDefault(false)

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) columnString(cursor, OpenableColumns.DISPLAY_NAME) else null
        }
    }.getOrNull()

    /**
     * Name / size / modification time of a document, as far as its provider reports them.
     *
     * `last_modified` is a `DocumentsContract` column, not an `OpenableColumns` one, and
     * providers that are not document providers reject unknown projection columns — so
     * the query degrades to name + size instead of failing outright.
     */
    private fun metadataOf(uri: Uri): DocumentMetadata =
        queryMetadata(uri, includeModified = true)
            ?: queryMetadata(uri, includeModified = false)
            ?: DocumentMetadata(null, null, null)

    private fun queryMetadata(uri: Uri, includeModified: Boolean): DocumentMetadata? = runCatching {
        val projection = if (includeModified) {
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
        } else {
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        }
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                DocumentMetadata(null, null, null)
            } else {
                DocumentMetadata(
                    name = columnString(cursor, OpenableColumns.DISPLAY_NAME),
                    size = columnLong(cursor, OpenableColumns.SIZE),
                    lastModified = if (includeModified) {
                        columnLong(cursor, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    } else {
                        null
                    },
                )
            }
        }
    }.getOrNull()

    private fun columnString(cursor: Cursor, column: String): String? {
        val index = cursor.getColumnIndex(column)
        return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
    }

    private fun columnLong(cursor: Cursor, column: String): Long? {
        val index = cursor.getColumnIndex(column)
        return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
    }

    /** Outcome of reading a document's bytes. */
    sealed class TextResult {
        class Success(val text: String) : TextResult()
        object TooLarge : TextResult()
        object Failed : TextResult()
    }

    /**
     * Reads a document as text. A hard byte ceiling keeps a pathological file from
     * exhausting memory (a crash would violate project book §9), and a BOM is honoured
     * so that UTF-8/UTF-16 files saved by desktop editors open cleanly.
     */
    suspend fun readText(uri: Uri, maxBytes: Int = MAX_DOCUMENT_BYTES): TextResult =
        withContext(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: return@withContext TextResult.Failed
                val bytes = stream.use { input -> readAtMost(input, maxBytes) }
                    ?: return@withContext TextResult.TooLarge
                log("read: uri=$uri bytes=${bytes.size}")
                TextResult.Success(decodeText(bytes))
            } catch (error: Exception) {
                log("read failed: uri=$uri (${error.message})")
                TextResult.Failed
            }
        }

    private fun readAtMost(input: InputStream, maxBytes: Int): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream(minOf(maxBytes, INITIAL_BUFFER_BYTES))
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return String(bytes, Charsets.UTF_8)
    }

    // ------------------------------------------------------- relative resolution

    /**
     * Resolves a relative Markdown reference against the directory that contains
     * [context]'s document. Returns `null` when the reference is unsafe, escapes the
     * authorised tree, the document has no known parent, or the provider cannot be
     * traversed.
     */
    suspend fun resolveRelative(context: DocumentContext, rawReference: String): ResourceTarget? =
        withContext(Dispatchers.IO) {
            val segments = UrlPolicy.relativeSegments(rawReference)
            if (segments == null) {
                log("relative: '$rawReference' refused by the reference policy")
                return@withContext null
            }
            val treeUri = context.treeUri
            if (treeUri == null) {
                log("relative: '$rawReference' skipped, document is not inside an authorised tree")
                return@withContext null
            }
            val chain = context.ancestorDocumentIds
            if (chain.isEmpty()) {
                log("relative: '$rawReference' skipped, parent directory is unknown")
                return@withContext null
            }

            var directoryIndex = chain.size - 1
            var index = 0
            while (index < segments.size && segments[index] == "..") {
                directoryIndex--
                index++
                if (directoryIndex < 0) {
                    log("relative: '$rawReference' escapes the authorised tree root")
                    return@withContext null
                }
            }

            var currentDocumentId = chain[directoryIndex]
            while (index < segments.size) {
                val child = findChildByName(treeUri, currentDocumentId, segments[index])
                if (child == null) {
                    log("relative: '$rawReference' -> '${segments[index]}' not found below $currentDocumentId")
                    return@withContext null
                }
                if (index == segments.size - 1) {
                    val mime = MimeTypes.resolveResource(child.name, child.mimeType)
                    log(
                        "relative: '$rawReference' -> ${child.uri}" +
                            " declaredMime=${child.mimeType} servedMime=$mime" +
                            " size=${child.size} isImage=${MimeTypes.isImage(mime)}"
                    )
                    return@withContext ResourceTarget(child.uri, mime)
                }
                if (!child.isDirectory) {
                    log("relative: '$rawReference' -> '${segments[index]}' is not a directory")
                    return@withContext null
                }
                currentDocumentId = child.documentId
                index++
            }
            null
        }

    // ------------------------------------------------------------ tree discovery

    private fun persistedTreeUris(): List<Uri> = runCatching {
        contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
            // MV-14: only real tree grants can serve as a resolution base. The same system list
            // also holds a persisted grant for every single document that ever arrived from
            // outside (a shared file, a media item opened with MarkView), and those were skipped
            // one by one on every open.
            .filter { DocumentsContract.isTreeUri(it) }
    }.getOrDefault(emptyList())

    private fun findTreeAssociation(documentUri: Uri): TreeAssociation? {
        val key = documentUri.toString()
        associationCache[key]?.let {
            log("tree association (cached): ${it.kind} via ${it.treeUri}")
            return it
        }
        if (noAssociationCache.contains(key)) {
            log("tree association (cached): none")
            return null
        }
        val association = computeTreeAssociation(documentUri)
        if (association == null) noAssociationCache.add(key) else associationCache[key] = association
        return association
    }

    private fun computeTreeAssociation(documentUri: Uri): TreeAssociation? {
        val documentAuthority = documentUri.authority
        val documentId = documentIdOf(documentUri)
        val claimedTreeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(documentUri) }.getOrNull()
        val metadata = metadataOf(documentUri)
        val trees = persistedTreeUris()

        log(
            "tree lookup: authority=$documentAuthority documentId=$documentId" +
                " uriTreeDocumentId=$claimedTreeDocumentId" +
                " name=${metadata.name} size=${metadata.size} modified=${metadata.lastModified}" +
                " persistedTrees=${trees.size}"
        )

        // A document can match several authorised trees only when those trees are nested
        // (document ids are unique per provider, so two unrelated trees cannot both contain
        // the same document). The tree that sits highest up therefore produces the longest
        // ancestor chain, and that is the one preferred here: it keeps `../` references
        // available for the widest authorised area. See TreePathPolicy.preferShallower.
        var preferred: TreeAssociation? = null
        var matchingTrees = 0
        val pendingSearch = ArrayList<Pair<Uri, String>>()

        // Pass 1: traversal-free matches (id equality, hierarchical id path). Deliberately not
        // returning on the first hit — a later, shallower tree must still be able to win.
        for (treeUri in trees) {
            val treeAuthority = treeUri.authority
            val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            if (treeDocumentId == null) {
                log("  tree $treeUri skipped: no tree document id")
                continue
            }
            if (!TreePathPolicy.sameAuthority(documentAuthority, treeAuthority)) {
                log("  tree $treeUri skipped: authority $treeAuthority does not match $documentAuthority")
                continue
            }

            val candidate = cheapAssociation(treeUri, treeDocumentId, documentId)
            if (candidate != null) {
                matchingTrees += 1
                preferred = pickPreferred(preferred, candidate)
            } else {
                pendingSearch.add(treeUri to treeDocumentId)
            }
        }

        if (preferred != null) {
            logDecision(preferred, matchingTrees)
            return preferred
        }

        // Pass 2: bounded traversal, only for trees that produced no cheap match.
        val identityCandidates = ArrayList<TreeAssociation>()
        for ((treeUri, treeDocumentId) in pendingSearch) {
            val outcome = searchTree(treeUri, treeDocumentId, documentId, documentUri, metadata)
            val exact = outcome.exactChain
            if (exact != null) {
                matchingTrees += 1
                preferred = pickPreferred(preferred, TreeAssociation(treeUri, exact, outcome.exactKind))
            }
            for (chain in outcome.identityChains) {
                identityCandidates.add(TreeAssociation(treeUri, chain, MatchKind.CONTENT_IDENTITY))
            }
        }

        if (preferred != null) {
            logDecision(preferred, matchingTrees)
            return preferred
        }

        // Same-provider content identity (name + size + modification time): the cheapest
        // attribution, and the one the first round already used. A unique candidate wins here
        // exactly as before.
        ContentIdentityPolicy.uniqueOrNull(identityCandidates)?.let { unique ->
            log("tree association by content identity: ${unique.treeUri}")
            return unique
        }
        if (identityCandidates.size > 1) {
            log("tree identity is ambiguous in one provider (${identityCandidates.size} equally named files), comparing content")
        }

        // Pass 3 (MV-14): the delivered document may come from a provider that owns none of the
        // authorised trees — a file manager's own FileProvider, the downloads provider, a share
        // sheet's cached copy. Document ids are useless there (absent, or belonging to another
        // provider), so the file is located by name and *proved* by its content: the digest of
        // the delivered bytes must equal the digest of a file inside an authorised tree.
        val digestAssociations = contentIdentityAssociations(trees, metadata, documentUri)
        ContentIdentityPolicy.uniqueOrNull(digestAssociations)?.let { unique ->
            log("tree association by content digest (cross-provider): ${unique.treeUri}")
            return unique
        }
        if (digestAssociations.size > 1) {
            log("tree association ambiguous by content digest: ${digestAssociations.size} identical files, refusing to guess")
            return null
        }

        log("tree association: none (relative resources will be reported as missing)")
        return null
    }

    /** Document-id equality or a hierarchical id path below the tree root; no traversal. */
    private fun cheapAssociation(
        treeUri: Uri,
        treeDocumentId: String,
        documentId: String?,
    ): TreeAssociation? {
        if (documentId == null) return null
        if (documentId == treeDocumentId) {
            return TreeAssociation(treeUri, listOf(treeDocumentId), MatchKind.DOCUMENT_ID)
        }
        val relative = TreePathPolicy.relativePathWithinTree(documentId, treeDocumentId) ?: return null
        val directories = TreePathPolicy.directorySegments(relative)
        val chain = walkDown(treeUri, treeDocumentId, directories)
        if (chain == null) {
            log("  tree $treeUri: '$relative' is a hierarchical id but a directory lookup failed")
            return null
        }
        return TreeAssociation(treeUri, chain, MatchKind.DERIVED_PATH)
    }

    /**
     * Keeps the outermost of two trees that both contain the document, i.e. the one with the
     * longer ancestor chain (see [TreePathPolicy.preferShallower]).
     */
    private fun pickPreferred(current: TreeAssociation?, candidate: TreeAssociation): TreeAssociation =
        if (TreePathPolicy.preferShallower(current?.ancestorDocumentIds?.size, candidate.ancestorDocumentIds.size)) {
            candidate
        } else {
            current ?: candidate
        }

    private fun logDecision(association: TreeAssociation, matchingTrees: Int) {
        log(
            "tree decision: $matchingTrees matching tree(s), chose ${association.treeUri}" +
                " match=${association.kind} chainDepth=${association.ancestorDocumentIds.size}" +
                (if (matchingTrees > 1) " (outermost tree preferred)" else "")
        )
    }

    /** Walks `root` + [directorySegments] down by display name, collecting the document-id chain. */
    private fun walkDown(treeUri: Uri, rootDocumentId: String, directorySegments: List<String>): List<String>? {
        if (directorySegments.isEmpty()) return listOf(rootDocumentId)
        val chain = ArrayList<String>(directorySegments.size + 1)
        chain.add(rootDocumentId)
        var current = rootDocumentId
        for (segment in directorySegments) {
            if (segment.isEmpty()) continue
            val child = findChildByName(treeUri, current, segment) ?: return null
            if (!child.isDirectory) return null
            current = child.documentId
            chain.add(current)
        }
        return chain
    }

    /**
     * Bounded breadth-first search of one tree for [targetDocumentId]: first by exact
     * document id / URI, and in the same pass collects directories that hold a unique
     * content-identity match for providers that hand over the same file under an
     * unrelated URI (MV-01). Both are required to keep the search off the UI thread.
     */
    private fun searchTree(
        treeUri: Uri,
        rootDocumentId: String,
        targetDocumentId: String?,
        targetUri: Uri,
        targetMetadata: DocumentMetadata,
    ): SearchOutcome {
        val identityChains = ArrayList<List<String>>()
        val queue = ArrayDeque<Pair<String, List<String>>>()
        queue.addLast(rootDocumentId to listOf(rootDocumentId))
        var inspected = 0

        while (queue.isNotEmpty() && inspected < SEARCH_BUDGET) {
            val (directoryId, chain) = queue.removeFirst()
            if (chain.size > MAX_SEARCH_DEPTH) continue
            val children = listChildren(treeUri, directoryId) ?: continue
            for (child in children) {
                inspected++
                if (child.uri == targetUri) {
                    return SearchOutcome(chain, MatchKind.URI_SEARCH, identityChains)
                }
                if (targetDocumentId != null && child.documentId == targetDocumentId) {
                    return SearchOutcome(chain, MatchKind.DOCUMENT_ID_SEARCH, identityChains)
                }
                if (targetMetadata.usableForIdentity && !child.isDirectory &&
                    isSameContent(child, targetMetadata)
                ) {
                    log("  identity candidate: ${child.uri} in $directoryId")
                    identityChains.add(chain)
                }
                if (child.isDirectory) {
                    queue.addLast(child.documentId to (chain + child.documentId))
                }
                if (inspected >= SEARCH_BUDGET) break
            }
        }
        return SearchOutcome(null, MatchKind.NONE, identityChains)
    }

    /**
     * Content identity never relies on the file name alone: the size must be equal and,
     * when both sides report a modification time, it must match within a tolerance.
     */
    private fun isSameContent(child: ChildEntry, target: DocumentMetadata): Boolean {
        val targetName = target.name ?: return false
        if (child.name != targetName) return false
        val targetSize = target.size ?: return false
        if (child.size != targetSize) return false
        val targetModified = target.lastModified
        if (targetModified != null && targetModified > 0 && child.lastModified > 0) {
            return abs(child.lastModified - targetModified) <= LAST_MODIFIED_TOLERANCE_MS
        }
        return true
    }

    // ------------------------------------------------- content identity (MV-14)

    /** A file inside an authorised tree whose name (and size) matches the delivered document. */
    private class NameCandidate(
        val documentId: String,
        val uri: Uri,
        val size: Long,
        val chain: List<String>,
    )

    private class NameSearchOutcome(val candidates: List<NameCandidate>, val inspected: Int)

    /**
     * Bounded breadth-first search of one tree for files called [name], narrowed further by
     * [size] when the delivering provider reports one.
     *
     * A name only *narrows* the candidates here: [ContentIdentityPolicy.isSameContent] compares
     * the bytes before anything is bound, so a same-named file is never accepted on its own
     * (MV-01: never guess). The bounds are the ones the exact search already uses — depth and
     * entries per open — plus a cap on candidates per tree, so a huge tree still cannot stall
     * opening a document.
     */
    private fun searchTreeByName(
        treeUri: Uri,
        rootDocumentId: String,
        name: String,
        size: Long?,
    ): NameSearchOutcome {
        val candidates = ArrayList<NameCandidate>()
        val queue = ArrayDeque<Pair<String, List<String>>>()
        queue.addLast(rootDocumentId to listOf(rootDocumentId))
        var inspected = 0

        while (queue.isNotEmpty() && inspected < SEARCH_BUDGET && candidates.size <= IDENTITY_READ_BUDGET) {
            val (directoryId, chain) = queue.removeFirst()
            if (chain.size > MAX_SEARCH_DEPTH) continue
            val children = listChildren(treeUri, directoryId) ?: continue
            for (child in children) {
                inspected++
                if (!child.isDirectory &&
                    ContentIdentityPolicy.namesAgree(name, child.name) &&
                    ContentIdentityPolicy.sizesAgree(size, child.size)
                ) {
                    candidates.add(NameCandidate(child.documentId, child.uri, child.size, chain))
                }
                if (child.isDirectory) {
                    queue.addLast(child.documentId to (chain + child.documentId))
                }
                if (inspected >= SEARCH_BUDGET) break
            }
        }
        return NameSearchOutcome(candidates, inspected)
    }

    /**
     * SHA-256 over up to [IDENTITY_DIGEST_BYTES] bytes of [uri]. `truncated` marks a file that
     * continues past that window, in which case the reported sizes have to agree as well — see
     * [ContentIdentityPolicy.isSameContent]. Returning `null` means "not readable", i.e. simply
     * no match; it is never turned into an error.
     */
    private fun fingerprintOf(uri: Uri, sizeHint: Long?): ContentFingerprint? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        var truncated = false
        val stream = contentResolver.openInputStream(uri) ?: return null
        stream.use { input ->
            while (total < IDENTITY_DIGEST_BYTES) {
                val limit = minOf(buffer.size, IDENTITY_DIGEST_BYTES - total)
                val read = input.read(buffer, 0, limit)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                total += read
            }
            // A single byte past the window proves the file is longer than the digest covers.
            truncated = input.read() >= 0
        }
        ContentFingerprint(
            size = if (truncated) sizeHint?.takeIf { it >= 0 } else total.toLong(),
            digest = hexOf(digest.digest()),
            truncated = truncated,
        )
    }.getOrElse { error ->
        log("content digest failed for $uri (${error.message})")
        null
    }

    private fun hexOf(bytes: ByteArray): String {
        val text = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            text.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return text.toString()
    }

    /**
     * Looks for the delivered document in **every** authorised tree, whatever provider it came
     * from, and proves each candidate by content.
     *
     * Matches are grouped by document id, so the *same* file reached through two nested
     * authorised trees counts once and the outermost tree wins — the rule [pickPreferred]
     * already implements for the id-based passes. What is left has to be a single file; several
     * identical files mean the document cannot be attributed to one directory, and binding any
     * of them would attach the wrong relative resources.
     */
    private fun contentIdentityAssociations(
        trees: List<Uri>,
        metadata: DocumentMetadata,
        documentUri: Uri,
    ): List<TreeAssociation> {
        val name = metadata.name
        if (name.isNullOrBlank()) {
            log("content identity: the provider reports no file name, cannot locate the document")
            return emptyList()
        }
        val reportedSize = metadata.size?.takeIf { it >= 0 }
        val byDocumentId = LinkedHashMap<String, TreeAssociation>()
        var targetFingerprint: ContentFingerprint? = null

        for (treeUri in trees) {
            val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: continue
            val outcome = searchTreeByName(treeUri, treeDocumentId, name, reportedSize)
            if (outcome.candidates.isEmpty()) continue
            log(
                "content identity: ${outcome.candidates.size} file(s) named '$name' in $treeUri" +
                    " (${outcome.inspected} entries inspected)",
            )

            if (targetFingerprint == null) {
                targetFingerprint = fingerprintOf(documentUri, reportedSize)
                if (targetFingerprint == null) {
                    log("content identity: the delivered document cannot be read, no match possible")
                    return emptyList()
                }
            }

            var compared = 0
            for (candidate in outcome.candidates) {
                if (compared >= IDENTITY_READ_BUDGET) break
                val candidateFingerprint = fingerprintOf(candidate.uri, candidate.size) ?: continue
                compared++
                if (!ContentIdentityPolicy.isSameContent(targetFingerprint, candidateFingerprint)) continue
                val association =
                    TreeAssociation(treeUri, candidate.chain, MatchKind.CONTENT_IDENTITY_CROSS_PROVIDER)
                val existing = byDocumentId[candidate.documentId]
                byDocumentId[candidate.documentId] =
                    if (existing == null) association else pickPreferred(existing, association)
                log("content identity confirmed: ${candidate.uri} via $treeUri")
            }
        }
        return byDocumentId.values.toList()
    }

    private fun findChildByName(treeUri: Uri, directoryDocumentId: String, name: String): ChildEntry? {
        val children = listChildren(treeUri, directoryDocumentId) ?: return null
        children.firstOrNull { it.name == name }?.let { return it }
        return children.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun listChildren(treeUri: Uri, directoryDocumentId: String): List<ChildEntry>? {
        val cacheKey = "$treeUri|$directoryDocumentId"
        listCache[cacheKey]?.let { return it }

        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryDocumentId)
        }.getOrNull() ?: return null

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        val result = ArrayList<ChildEntry>()
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(childrenUri, projection, null, null, null)
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0) ?: continue
                    val childName = cursor.getString(1) ?: continue
                    val childMime = cursor.getString(2) ?: ""
                    val childUri = runCatching {
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    }.getOrNull() ?: continue
                    result.add(
                        ChildEntry(
                            documentId = childId,
                            uri = childUri,
                            name = childName,
                            mimeType = childMime,
                            isDirectory = childMime == DocumentsContract.Document.MIME_TYPE_DIR,
                            size = columnLong(cursor, DocumentsContract.Document.COLUMN_SIZE) ?: UNKNOWN_SIZE,
                            lastModified = columnLong(cursor, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                                ?: UNKNOWN_MODIFIED,
                        )
                    )
                }
            }
        } catch (error: Exception) {
            // Providers may refuse traversal entirely; relative resources stay missing.
            log("listChildren failed for $directoryDocumentId in $treeUri (${error.message})")
            return null
        } finally {
            runCatching { cursor?.close() }
        }

        listCache[cacheKey] = result
        return result
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "MarkView"
        const val FALLBACK_NAME = "未命名文档"
        const val MAX_DOCUMENT_BYTES = 16 * 1024 * 1024
        const val INITIAL_BUFFER_BYTES = 64 * 1024
        const val READ_CHUNK_BYTES = 16 * 1024
        const val LIST_CACHE_ENTRIES = 512
        const val MAX_SEARCH_DEPTH = 8
        const val SEARCH_BUDGET = 3000
        const val UNKNOWN_SIZE = -1L
        const val UNKNOWN_MODIFIED = -1L
        const val LAST_MODIFIED_TOLERANCE_MS = 2000L

        /** Digest window and read cap for the cross-provider content identity pass (MV-14). */
        const val IDENTITY_DIGEST_BYTES = 256 * 1024
        const val IDENTITY_READ_BUDGET = 8
        val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }
}
