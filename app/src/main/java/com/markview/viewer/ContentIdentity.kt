package com.markview.viewer

/**
 * Content identity for the cross-provider fallback (MV-14).
 *
 * A document can arrive from a provider that is *not* the one owning the authorised tree: a
 * file manager's own FileProvider, the downloads provider, the share sheet's cached copy. For
 * those URIs document ids mean nothing (there is no document id at all, or it belongs to
 * another provider) and no directory can be derived, so the only reliable statement about the
 * document is **what it contains**: the file name narrows the candidates inside the authorised
 * trees and a digest of the bytes decides. Names alone never select anything.
 *
 * [ContentFingerprint] is one side of that comparison: the size the provider reports (optional —
 * several providers omit `OpenableColumns.SIZE`) plus a SHA-256 over up to `limit` bytes, with
 * `truncated` recording that the digest covers only the first `limit` bytes of a longer file.
 */
data class ContentFingerprint(
    val size: Long?,
    val digest: String,
    val truncated: Boolean,
)

object ContentIdentityPolicy {

    /** Names must match exactly; a renamed or decorated copy is not the same file. */
    fun namesAgree(targetName: String?, candidateName: String?): Boolean {
        if (targetName.isNullOrBlank() || candidateName.isNullOrBlank()) return false
        return targetName == candidateName
    }

    /**
     * Sizes must agree when both sides report one. An unknown size (`null` or negative) is not
     * a mismatch: the digest below is what decides, which is what lets a provider that omits
     * the size column still be matched.
     */
    fun sizesAgree(targetSize: Long?, candidateSize: Long?): Boolean {
        if (targetSize == null || candidateSize == null) return true
        if (targetSize < 0 || candidateSize < 0) return true
        return targetSize == candidateSize
    }

    /**
     * Two fingerprints describe the same content when their digests match and — for a truncated
     * pair, i.e. a file longer than the digest window — their sizes also agree. A complete digest
     * is proof on its own.
     */
    fun isSameContent(target: ContentFingerprint?, candidate: ContentFingerprint?): Boolean {
        if (target == null || candidate == null) return false
        if (target.digest != candidate.digest) return false
        if (!target.truncated && !candidate.truncated) return true
        val targetSize = target.size ?: return false
        val candidateSize = candidate.size ?: return false
        if (targetSize < 0 || candidateSize < 0) return false
        return targetSize == candidateSize
    }

    /**
     * The only candidate that may be used. Several equally valid candidates mean the document
     * cannot be attributed to a single directory, and guessing would attach the wrong relative
     * resources — so nothing is chosen (MV-01: never guess).
     */
    fun <T> uniqueOrNull(candidates: List<T>): T? = if (candidates.size == 1) candidates[0] else null
}
