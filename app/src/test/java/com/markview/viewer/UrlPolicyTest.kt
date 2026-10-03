package com.markview.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole reference policy is pure JDK code, so every rule from project book §5.2
 * and §7 is covered here: decoding first, separator unification, refusal of absolute
 * paths and schemes, and `.`/`..` normalisation with leading `..` preserved for the
 * SAF layer to validate against the authorised tree root.
 */
class UrlPolicyTest {

    @Test
    fun `plain relative path is accepted`() {
        assertEquals(listOf("image.png"), UrlPolicy.relativeSegments("image.png"))
        assertEquals(listOf("images", "image.png"), UrlPolicy.relativeSegments("images/image.png"))
    }

    @Test
    fun `dot segments are dropped`() {
        assertEquals(listOf("images", "image.png"), UrlPolicy.relativeSegments("./images/image.png"))
        assertEquals(listOf("a", "b"), UrlPolicy.relativeSegments("a/./b"))
        assertEquals(listOf("b"), UrlPolicy.relativeSegments("a/../b"))
    }

    @Test
    fun `leading parent references are preserved for the resolver`() {
        assertEquals(listOf("..", "images", "image.png"), UrlPolicy.relativeSegments("../images/image.png"))
        assertEquals(listOf("..", "..", "top.png"), UrlPolicy.relativeSegments("../../top.png"))
        // `a/../..` cancels out to a single level above the document directory.
        assertEquals(listOf("..", "top.png"), UrlPolicy.relativeSegments("a/../../top.png"))
    }

    @Test
    fun `percent encoded paths are decoded before validation`() {
        assertEquals(listOf("图片", "a b.png"), UrlPolicy.relativeSegments("%E5%9B%BE%E7%89%87/a%20b.png"))
        // %2e%2e%2f must not survive as a traversal.
        assertEquals(listOf("..", "secret.png"), UrlPolicy.relativeSegments("%2e%2e%2fsecret.png"))
    }

    @Test
    fun `query and fragment are stripped before decoding`() {
        assertEquals(listOf("a.png"), UrlPolicy.relativeSegments("a.png?raw=1"))
        assertEquals(listOf("a.png"), UrlPolicy.relativeSegments("a.png#frag"))
        // %23 is a literal hash, not a fragment separator.
        assertEquals(listOf("a#b.png"), UrlPolicy.relativeSegments("a%23b.png"))
    }

    @Test
    fun `absolute paths and schemes are refused`() {
        assertNull(UrlPolicy.relativeSegments("/etc/passwd"))
        assertNull(UrlPolicy.relativeSegments("//example.com/a.png"))
        assertNull(UrlPolicy.relativeSegments("content://com.example/a.png"))
        assertNull(UrlPolicy.relativeSegments("file:///sdcard/a.png"))
        assertNull(UrlPolicy.relativeSegments("javascript:alert(1)"))
        assertNull(UrlPolicy.relativeSegments("data:text/html,<script>"))
        assertNull(UrlPolicy.relativeSegments("%2Fetc%2Fpasswd"))
    }

    @Test
    fun `backslashes are unified into separators`() {
        assertEquals(listOf("images", "a.png"), UrlPolicy.relativeSegments("images\\a.png"))
        assertNull(UrlPolicy.relativeSegments("\\windows\\a.png"))
    }

    @Test
    fun `empty and malformed references are refused`() {
        assertNull(UrlPolicy.relativeSegments(""))
        assertNull(UrlPolicy.relativeSegments("   "))
        assertNull(UrlPolicy.relativeSegments("."))
        assertNull(UrlPolicy.relativeSegments("./"))
        assertNull(UrlPolicy.relativeSegments("a%2"))
        assertNull(UrlPolicy.relativeSegments("a\u0000b.png"))
    }

    @Test
    fun `http references are recognised`() {
        assertTrue(UrlPolicy.isHttpReference("http://example.com/a.png"))
        assertTrue(UrlPolicy.isHttpReference("HTTPS://example.com/a.png"))
        assertFalse(UrlPolicy.isHttpReference("images/a.png"))
    }

    @Test
    fun `percent decoding keeps plus signs literal`() {
        assertEquals("a+b.png", UrlPolicy.percentDecode("a+b.png"))
        assertEquals("a b.png", UrlPolicy.percentDecode("a%20b.png"))
        assertNull(UrlPolicy.percentDecode("%zz"))
    }

    @Test
    fun `only markdown references are followed natively`() {
        assertTrue(UrlPolicy.looksLikeMarkdownReference("other.md", null, null))
        assertTrue(UrlPolicy.looksLikeMarkdownReference("../docs/other.markdown", null, null))
        assertFalse(UrlPolicy.looksLikeMarkdownReference("other.png", null, null))
        assertFalse(UrlPolicy.looksLikeMarkdownReference("notes", null, null))
        assertFalse(UrlPolicy.looksLikeMarkdownReference("https://example.com/a.md", null, null))
        assertFalse(UrlPolicy.looksLikeMarkdownReference("#anchor", null, null))
    }
}
