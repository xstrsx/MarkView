package com.markview.viewer

/**
 * File-type recognition for the Markdown documents MarkView accepts.
 *
 * Pure JDK code so it is directly unit-testable. The typical extensions and MIME
 * types follow project book §4.3.
 */
object MarkdownFileType {

    /** Typical Markdown extensions, lowercase, without the dot. */
    val EXTENSIONS: Set<String> = setOf(
        "md",
        "markdown",
        "mdown",
        "mkdn",
        "mkd",
        "mdtxt",
        "mdtext",
    )

    /** Typical Markdown MIME types reported by document providers. */
    val MIME_TYPES: Set<String> = setOf(
        "text/markdown",
        "text/x-markdown",
        "text/plain",
    )

    fun extensionOf(fileName: String?): String? {
        val name = fileName?.trim().orEmpty()
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase()
    }

    /** `true` when either the MIME type or the file name identifies a Markdown document. */
    fun isSupported(fileName: String?, mimeType: String?): Boolean {
        val mime = mimeType?.trim()?.lowercase()?.substringBefore(';')?.trim()
        if (mime != null && mime in MIME_TYPES) return true
        val extension = extensionOf(fileName) ?: return false
        return extension in EXTENSIONS
    }

    /** MIME types requested from the system picker, plus an "any" fallback for broken file managers. */
    val PICKER_MIME_TYPES: Array<String> = arrayOf(
        "text/markdown",
        "text/x-markdown",
        "text/plain",
        "*/*",
    )
}
