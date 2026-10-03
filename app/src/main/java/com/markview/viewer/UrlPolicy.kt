package com.markview.viewer

/**
 * Pure string policy for every reference that comes out of a Markdown document.
 *
 * This class deliberately depends on nothing but the JDK so that the whole policy
 * can be covered by fast JVM unit tests. All Android/SAF reading happens in
 * [SafDocumentResolver]; this file only decides *what* is allowed to be looked up.
 *
 * Rules enforced here (see project book §5.2, §7, §9):
 *  - percent decoding first, so `%2e%2e/` cannot smuggle traversal through;
 *  - path separators unified to `/`;
 *  - absolute paths, URI schemes, backslash-escapes and NUL bytes refused;
 *  - `.` / `..` normalised, with leading `..` kept so the SAF layer can walk up
 *    towards the authorised tree root and refuse anything above it.
 */
object UrlPolicy {

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")

    fun isHttpReference(raw: String): Boolean {
        val s = raw.trim()
        return s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)
    }

    fun isAnchorReference(raw: String): Boolean = raw.trim().startsWith("#")

    /** True when the reference carries an explicit scheme (`content:`, `javascript:`, ...). */
    fun hasExplicitScheme(raw: String): Boolean {
        val s = raw.trim()
        if (s.startsWith("//")) return true
        return SCHEME.containsMatchIn(s)
    }

    /**
     * Splits a reference into its path part, dropping `#fragment` and `?query`
     * exactly as written (before percent decoding, so `%23` stays a literal `#`).
     */
    fun pathPartOf(raw: String): String {
        val s = raw.trim()
        val hash = s.indexOf('#').let { if (it >= 0) it else s.length }
        val query = s.indexOf('?').let { if (it >= 0) it else s.length }
        return s.substring(0, minOf(hash, query))
    }

    /** Percent decodes without the `+` → space conversion that URLDecoder applies to forms. */
    fun percentDecode(raw: String): String? {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '%'.code.toByte()) {
                if (i + 2 >= bytes.size) return null
                val hi = hexValue(bytes[i + 1]) ?: return null
                val lo = hexValue(bytes[i + 2]) ?: return null
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                out.write(b.toInt())
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hexValue(b: Byte): Int? = when (val c = b.toInt().toChar()) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    /**
     * Normalises a Markdown relative reference into path segments.
     *
     * Returns `null` when the reference must be refused outright: empty, absolute,
     * carrying a scheme, containing a NUL byte, or malformed percent escapes.
     *
     * `.` and empty segments are dropped, interior `..` cancels the previous
     * segment, and leading `..` are preserved for the caller to validate against
     * the authorised tree root.
     */
    fun relativeSegments(raw: String): List<String>? {
        val path = pathPartOf(raw)
        if (path.isEmpty()) return null
        val unified = path.replace('\\', '/')
        val decoded = percentDecode(unified) ?: return null
        if (decoded.indexOf('\u0000') >= 0) return null
        if (decoded.startsWith("/")) return null
        if (hasExplicitScheme(decoded)) return null

        val out = ArrayList<String>(8)
        for (segment in decoded.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty() && out[out.size - 1] != "..") {
                    out.removeAt(out.size - 1)
                } else {
                    out.add("..")
                }
                else -> out.add(segment)
            }
        }
        return if (out.isEmpty()) null else out
    }

    /** True when the reference is a relative Markdown document we may follow natively. */
    fun looksLikeMarkdownReference(raw: String, fileName: String?, mimeType: String?): Boolean {
        if (raw.isEmpty() || raw.startsWith("#")) return false
        if (isHttpReference(raw)) return false
        if (hasExplicitScheme(raw)) return false
        val name = fileName ?: pathPartOf(raw).substringAfterLast('/')
        return MarkdownFileType.isSupported(name, mimeType)
    }
}
