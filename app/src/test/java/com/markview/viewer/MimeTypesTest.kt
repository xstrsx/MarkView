package com.markview.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIME decisions for bridged local resources (MV-01).
 *
 * Only extensions covered by the built-in override table are used here, so the tests
 * stay pure JVM code and never touch `android.webkit.MimeTypeMap`.
 */
class MimeTypesTest {

    @Test
    fun `an svg declared as plain text is still served as an image`() {
        // This is the case that made SVG fail only for some providers: the same file is
        // described as image/svg+xml by external storage and as text/plain by others.
        assertEquals("image/svg+xml", MimeTypes.resolveResource("logo.svg", "text/plain"))
        assertEquals("image/svg+xml", MimeTypes.resolveResource("logo.svg", "text/xml"))
        assertEquals("image/svg+xml", MimeTypes.resolveResource("logo.svg", "application/octet-stream"))
        assertEquals("image/svg+xml", MimeTypes.resolveResource("logo.svg", null))
    }

    @Test
    fun `a declared image type is preferred`() {
        assertEquals("image/png", MimeTypes.resolveResource("photo.png", "image/png"))
        assertEquals("image/jpeg", MimeTypes.resolveResource("photo.jpg", "image/jpeg"))
        assertEquals("image/svg+xml", MimeTypes.resolveResource("logo.svg", "image/svg+xml"))
    }

    @Test
    fun `the file name repairs a missing or generic type for images`() {
        assertEquals("image/png", MimeTypes.resolveResource("photo.png", "application/octet-stream"))
        assertEquals("image/jpeg", MimeTypes.resolveResource("photo.jpeg", null))
        assertEquals("image/webp", MimeTypes.resolveResource("photo.webp", ""))
        assertEquals("image/gif", MimeTypes.resolveResource("anim.gif", null))
    }

    @Test
    fun `non image resources keep their declared type`() {
        assertEquals("text/plain", MimeTypes.resolveResource("notes.md", "text/plain"))
        assertEquals("text/plain", MimeTypes.resolveResource("notes.txt", null))
        assertEquals("text/markdown", MimeTypes.resolveResource("notes.md", null))
    }

    @Test
    fun `only image types may be bridged`() {
        assertTrue(MimeTypes.isImage("image/svg+xml"))
        assertTrue(MimeTypes.isImage("image/png"))
        assertFalse(MimeTypes.isImage("text/plain"))
        assertFalse(MimeTypes.isImage("application/octet-stream"))
        assertFalse(MimeTypes.isImage(""))
    }

    @Test
    fun `declared parameters and casing are normalised`() {
        assertEquals("image/jpeg", MimeTypes.resolveResource("photo.jpg", "IMAGE/JPEG; charset=binary"))
    }
}
