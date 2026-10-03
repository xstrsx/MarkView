package com.markview.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** File-type recognition rules from project book §4.3. */
class MarkdownFileTypeTest {

    @Test
    fun `typical extensions are recognised`() {
        assertTrue(MarkdownFileType.isSupported("notes.md", null))
        assertTrue(MarkdownFileType.isSupported("notes.markdown", null))
        assertTrue(MarkdownFileType.isSupported("notes.mdown", null))
        assertTrue(MarkdownFileType.isSupported("notes.mkdn", null))
        assertTrue(MarkdownFileType.isSupported("NOTES.MD", null))
    }

    @Test
    fun `typical mime types are recognised`() {
        assertTrue(MarkdownFileType.isSupported(null, "text/markdown"))
        assertTrue(MarkdownFileType.isSupported(null, "text/x-markdown"))
        assertTrue(MarkdownFileType.isSupported(null, "text/plain"))
        assertTrue(MarkdownFileType.isSupported("weird", "text/plain"))
        assertTrue(MarkdownFileType.isSupported("weird", "text/plain; charset=utf-8"))
    }

    @Test
    fun `unknown types are refused`() {
        assertFalse(MarkdownFileType.isSupported("photo.png", "image/png"))
        assertFalse(MarkdownFileType.isSupported("archive.zip", "application/zip"))
        assertFalse(MarkdownFileType.isSupported(null, null))
        assertFalse(MarkdownFileType.isSupported("noextension", "application/octet-stream"))
    }

    @Test
    fun `an octet-stream name still falls back to the extension`() {
        assertTrue(MarkdownFileType.isSupported("notes.md", "application/octet-stream"))
    }

    @Test
    fun `extension parsing handles odd names`() {
        assertEquals("md", MarkdownFileType.extensionOf("a.b.md"))
        assertEquals("md", MarkdownFileType.extensionOf(".hidden.md"))
        assertNull(MarkdownFileType.extensionOf("namedot."))
        assertNull(MarkdownFileType.extensionOf(".md"))
        assertNull(MarkdownFileType.extensionOf(null))
    }
}
