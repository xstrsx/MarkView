package com.markview.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules of the cross-provider content-identity fallback (MV-14).
 *
 * These decide whether a document that arrived from a foreign provider (a file manager's own
 * FileProvider, the downloads provider, a share sheet's cached copy) may be attributed to a
 * file inside an authorised tree — i.e. whether its relative images and links become readable.
 * The rules must stay strict enough that a wrong file can never be chosen, so every
 * combination of "what each side reports" is pinned here.
 */
class ContentIdentityPolicyTest {

    @Test
    fun `names must match exactly`() {
        assertTrue(ContentIdentityPolicy.namesAgree("全面.md", "全面.md"))
        assertFalse(ContentIdentityPolicy.namesAgree("全面.md", "全面 (1).md"))
        assertFalse(ContentIdentityPolicy.namesAgree("note.md", "NOTE.md"))
        assertFalse(ContentIdentityPolicy.namesAgree(null, "note.md"))
        assertFalse(ContentIdentityPolicy.namesAgree("note.md", null))
        assertFalse(ContentIdentityPolicy.namesAgree("", ""))
        assertFalse(ContentIdentityPolicy.namesAgree(" ", "note.md"))
    }

    @Test
    fun `sizes may only disagree when both sides report one`() {
        assertTrue(ContentIdentityPolicy.sizesAgree(18177L, 18177L))
        assertFalse(ContentIdentityPolicy.sizesAgree(18177L, 18178L))
        // Providers that omit OpenableColumns.SIZE must not be rejected here: the digest decides.
        assertTrue(ContentIdentityPolicy.sizesAgree(null, 18177L))
        assertTrue(ContentIdentityPolicy.sizesAgree(18177L, null))
        assertTrue(ContentIdentityPolicy.sizesAgree(null, null))
        // -1 is the resolver's "unknown" marker for a column the provider left empty.
        assertTrue(ContentIdentityPolicy.sizesAgree(-1L, 18177L))
        assertTrue(ContentIdentityPolicy.sizesAgree(18177L, -1L))
    }

    @Test
    fun `a complete digest is proof of identical content`() {
        val target = ContentFingerprint(size = 12L, digest = "abc", truncated = false)
        assertTrue(ContentIdentityPolicy.isSameContent(target, ContentFingerprint(12L, "abc", false)))
        assertFalse(ContentIdentityPolicy.isSameContent(target, ContentFingerprint(12L, "abd", false)))
        // Different reported sizes cannot change the outcome once the whole file was hashed.
        assertTrue(ContentIdentityPolicy.isSameContent(target, ContentFingerprint(null, "abc", false)))
    }

    @Test
    fun `a truncated digest needs the sizes to agree as well`() {
        val target = ContentFingerprint(size = 900_000L, digest = "abc", truncated = true)
        assertTrue(ContentIdentityPolicy.isSameContent(target, ContentFingerprint(900_000L, "abc", true)))
        assertFalse(ContentIdentityPolicy.isSameContent(target, ContentFingerprint(900_001L, "abc", true)))
        assertFalse(ContentIdentityPolicy.isSameContent(target, ContentFingerprint(null, "abc", true)))
        assertFalse(ContentIdentityPolicy.isSameContent(target, ContentFingerprint(-1L, "abc", true)))
        assertFalse(ContentIdentityPolicy.isSameContent(target, ContentFingerprint(900_000L, "abd", true)))
    }

    @Test
    fun `a missing fingerprint never matches`() {
        val fingerprint = ContentFingerprint(1L, "abc", false)
        assertFalse(ContentIdentityPolicy.isSameContent(null, fingerprint))
        assertFalse(ContentIdentityPolicy.isSameContent(fingerprint, null))
        assertFalse(ContentIdentityPolicy.isSameContent(null, null))
    }

    @Test
    fun `only a single candidate may be chosen`() {
        assertNull(ContentIdentityPolicy.uniqueOrNull(emptyList<String>()))
        assertEquals("only", ContentIdentityPolicy.uniqueOrNull(listOf("only")))
        // Two identical copies inside different authorised trees: refuse instead of guessing.
        assertNull(ContentIdentityPolicy.uniqueOrNull(listOf("a", "b")))
        assertNull(ContentIdentityPolicy.uniqueOrNull(listOf("a", "a")))
    }
}
