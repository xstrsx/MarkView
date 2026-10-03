package com.markview.viewer

/**
 * Pure document-id / authority policy for SAF trees (project book §5.2, §5.3; MV-01).
 *
 * Kept free of Android classes so that the authorization boundary can be covered by
 * fast JVM unit tests. Nothing here touches the file system: it only decides whether a
 * document id belongs to a tree, and how to walk between them.
 *
 * Two rules matter for correctness:
 *  - document ids are only comparable **within the same provider authority**. Comparing
 *    `primary:Docs/a.md` (external storage) with `msf:42` (downloads) would be
 *    meaningless, and a tree from another provider must never be used to derive a path.
 *  - only hierarchical document ids (`primary:Docs/sub/a.md`) may be split into a path;
 *    opaque ids (`msf:42`) must be located by traversal instead of string surgery;
 *  - when several authorised trees contain the same document those trees are necessarily
 *    nested, so [preferShallower] keeps the outermost one.
 */
object TreePathPolicy {

    /**
     * `""` when the document *is* the tree root, the path below the root when the
     * provider uses hierarchical document ids, `null` when the two are unrelated.
     */
    fun relativePathWithinTree(documentId: String, treeDocumentId: String): String? = when {
        documentId == treeDocumentId -> ""
        documentId.length > treeDocumentId.length &&
            documentId.startsWith(treeDocumentId) &&
            documentId[treeDocumentId.length] == SEPARATOR ->
            documentId.substring(treeDocumentId.length + 1)
        else -> null
    }

    /** Directory names between the tree root and the file's own directory. */
    fun directorySegments(relativePath: String): List<String> {
        if (relativePath.isEmpty()) return emptyList()
        val segments = relativePath.split(SEPARATOR)
        if (segments.size <= 1) return emptyList()
        return segments.dropLast(1).filter { it.isNotEmpty() }
    }

    /** File name part of a relative path. */
    fun fileName(relativePath: String): String = relativePath.substringAfterLast(SEPARATOR)

    /**
     * Whether a candidate tree should replace the one chosen so far while resolving one
     * document.
     *
     * [currentChainDepth] and [candidateChainDepth] are the lengths of the ancestor chains
     * (tree root … directory holding the document) of two trees that both contain that
     * document. Two trees can only both contain it when one is nested inside the other
     * (document ids are unique per provider), and the nested tree yields the shorter chain —
     * so the *deeper* chain belongs to the tree whose root sits higher up. Preferring it gives
     * relative references (`../`) the widest area the user actually authorised, and it makes
     * the outcome independent of the order in which the system returns the persisted grants.
     *
     * A tie keeps the current choice, so the result stays deterministic.
     */
    fun preferShallower(currentChainDepth: Int?, candidateChainDepth: Int): Boolean =
        currentChainDepth == null || candidateChainDepth > currentChainDepth

    /**
     * Whether a document URI and a tree URI come from the same provider. Ids from two
     * different providers are never comparable, so an unknown authority is a mismatch.
     */
    fun sameAuthority(documentAuthority: String?, treeAuthority: String?): Boolean {
        if (documentAuthority.isNullOrBlank() || treeAuthority.isNullOrBlank()) return false
        return documentAuthority.equals(treeAuthority, ignoreCase = true)
    }

    private const val SEPARATOR = '/'
}
