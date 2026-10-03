package com.markview.viewer

import android.webkit.MimeTypeMap

/**
 * MIME resolution for documents and for bridged local resources.
 *
 * SAF providers frequently report `application/octet-stream` (or nothing at all)
 * for perfectly ordinary images, so the file name is used as a fallback and the
 * extension map is consulted last.
 */
object MimeTypes {

    const val OCTET_STREAM = "application/octet-stream"

    private val OVERRIDES: Map<String, String> = mapOf(
        "svg" to "image/svg+xml",
        "svgz" to "image/svg+xml",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "jpe" to "image/jpeg",
        "jfif" to "image/jpeg",
        "png" to "image/png",
        "apng" to "image/png",
        "gif" to "image/gif",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "mdown" to "text/markdown",
        "mkdn" to "text/markdown",
        "txt" to "text/plain",
    )

    /** Best-effort MIME type from a file name, or `null` when nothing is known. */
    fun forName(fileName: String?): String? {
        val extension = MarkdownFileType.extensionOf(fileName) ?: return null
        OVERRIDES[extension]?.let { return it }
        return runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) }.getOrNull()
    }

    /** Prefers a usable declared type, then the file name, then `application/octet-stream`. */
    fun resolve(fileName: String?, declared: String?): String {
        val normalized = declared?.trim()?.lowercase()?.substringBefore(';')?.trim()
        if (!normalized.isNullOrEmpty() &&
            normalized != OCTET_STREAM &&
            normalized != "binary/octet-stream"
        ) {
            return normalized
        }
        return forName(fileName) ?: OCTET_STREAM
    }

    /** Only image resources may be bridged into the WebView page. */
    fun isImage(mimeType: String): Boolean = mimeType.startsWith("image/")

    private fun normalize(declared: String?): String? =
        declared?.trim()?.lowercase()?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * MIME type used when a resolved local resource is served to the WebView (MV-01).
     *
     * Documents providers describe the same file differently depending on which one
     * hands the URI over: an `.svg` is reported as `image/svg+xml` by external storage
     * but as `text/plain`, `text/xml` or `application/octet-stream` by several ROM
     * providers. Trusting those declarations made MarkView refuse perfectly valid SVG
     * images while PNG/JPEG (which every provider labels correctly) kept working.
     *
     * For a known image extension the file name therefore wins, and a declared
     * `image/...` type is still preferred when present. Only image types can ever be
     * bridged, so this does not widen what a document may reach.
     */
    fun resolveResource(fileName: String?, declared: String?): String {
        val declaredType = normalize(declared)
        if (declaredType != null && isImage(declaredType)) return declaredType

        val fromName = forName(fileName)
        if (fromName != null && isImage(fromName)) return fromName

        return declaredType ?: fromName ?: OCTET_STREAM
    }
}
