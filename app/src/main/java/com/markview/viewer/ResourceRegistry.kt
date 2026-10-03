package com.markview.viewer

import android.net.Uri
import java.security.SecureRandom

/**
 * Per-document registry that maps opaque, randomly generated tokens to real
 * `content://` URIs.
 *
 * The rendered page can therefore never construct a `content://` URL by itself:
 * it only ever sees `https://appassets.androidplatform.net/res/<token>`, and only
 * URIs that the native layer resolved inside the authorised tree were ever
 * registered here. Tokens are dropped whenever the visible document changes.
 */
class ResourceRegistry {

    class Entry(val uri: Uri, val mimeType: String)

    private val lock = Any()
    private val byToken = LinkedHashMap<String, Entry>()
    private val tokenByUri = HashMap<String, String>()
    private val random = SecureRandom()

    /** Registers [uri] and returns its token, reusing the token already bound to that URI. */
    fun register(uri: Uri, mimeType: String): String = synchronized(lock) {
        val key = uri.toString()
        val existing = tokenByUri[key]
        if (existing != null) {
            byToken[existing] = Entry(uri, mimeType)
            return existing
        }
        val token = newToken()
        byToken[token] = Entry(uri, mimeType)
        tokenByUri[key] = token
        token
    }

    fun lookup(token: String): Entry? = synchronized(lock) { byToken[token] }

    fun clear() = synchronized(lock) {
        byToken.clear()
        tokenByUri.clear()
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val builder = StringBuilder(32)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4])
            builder.append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
