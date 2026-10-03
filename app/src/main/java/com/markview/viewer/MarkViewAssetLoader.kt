package com.markview.viewer

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Builds the only two URL spaces the WebView is allowed to see (project book §5.4, MV-01):
 *
 *  - `https://appassets.androidplatform.net/assets/markview/...` — the bundled page,
 *    its CSS/JS and the vendor renderers, served straight from the APK;
 *  - `https://appassets.androidplatform.net/res/<token>` — one random token per
 *    resolved local resource, served from `ContentResolver`.
 *
 * Because a token is opaque and only native code can mint one, a document can never
 * construct a `content://` URL of its own, and nothing outside the authorised tree
 * ever reaches the page.
 *
 * Tokens are looked up in the current document's registry and in the previous one, so a
 * request that arrives while the page is still showing the previous document (or while
 * a newly opened document is being rendered) cannot fail spuriously.
 */
class MarkViewAssetLoader(
    private val context: Context,
    private val registryProvider: () -> List<ResourceRegistry>,
) {

    private val loader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain(HOST)
        .addPathHandler(ASSETS_PATH, WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler(RESOURCE_PATH, ResourcePathHandler())
        .build()

    fun shouldInterceptRequest(url: Uri): WebResourceResponse? = loader.shouldInterceptRequest(url)

    private inner class ResourcePathHandler : WebViewAssetLoader.PathHandler {

        override fun handle(path: String): WebResourceResponse {
            val token = path.substringBefore('/')
            if (!TOKEN_PATTERN.matches(token)) return notFound(token, "malformed-token")

            val entry = registryProvider()
                .firstNotNullOfOrNull { registry -> registry.lookup(token) }
                ?: return notFound(token, "unknown-token")

            // Defence in depth: only image resources are bridgeable, whatever the
            // registry might contain.
            if (!MimeTypes.isImage(entry.mimeType)) {
                return notFound(token, "not-an-image:${entry.mimeType}")
            }

            val stream = runCatching { context.contentResolver.openInputStream(entry.uri) }
                .getOrNull()
                ?: return notFound(token, "open-failed:${entry.uri}")

            Log.d(TAG, "serve token=$token mime=${entry.mimeType} uri=${entry.uri}")
            return WebResourceResponse(
                entry.mimeType,
                null,
                HTTP_OK,
                "OK",
                SECURITY_HEADERS,
                CountingInputStream(stream, token, entry.mimeType),
            )
        }

        private fun notFound(token: String, reason: String): WebResourceResponse {
            Log.d(TAG, "resource not served: token=$token reason=$reason")
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                HTTP_NOT_FOUND,
                "Not Found",
                SECURITY_HEADERS,
                ByteArrayInputStream(ByteArray(0)),
            )
        }
    }

    /** Reports how many bytes actually reached the WebView, which makes an empty or
     *  truncated local file visible in logcat instead of silently "not displaying". */
    private class CountingInputStream(
        private val delegate: InputStream,
        private val token: String,
        private val mimeType: String,
    ) : InputStream() {

        private var total = 0L

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) total += 1
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = delegate.read(buffer, offset, length)
            if (count > 0) total += count
            return count
        }

        override fun available(): Int = delegate.available()

        override fun close() {
            Log.d(TAG, "served token=$token mime=$mimeType bytes=$total")
            runCatching { delegate.close() }
        }
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"

        private const val TAG = "MarkView"
        private const val ASSETS_PATH = "/assets/"
        private const val RESOURCE_PATH = "/res/"

        /** URL of the single page the WebView ever loads. */
        const val INDEX_URL = "https://$HOST/assets/markview/index.html"

        /** Prefix for tokenised local resources; the page only ever sees completed URLs. */
        const val RESOURCE_URL_PREFIX = "https://$HOST$RESOURCE_PATH"

        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404

        private val TOKEN_PATTERN = Regex("^[0-9a-f]{32}$")

        private val SECURITY_HEADERS: Map<String, String> = mapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
        )
    }
}
