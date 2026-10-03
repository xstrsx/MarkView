package com.markview.viewer

import androidx.core.net.toUri
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Navigation and request policy for the viewer WebView (project book §7, §9).
 *
 * The page may only ever live at [MarkViewAssetLoader.INDEX_URL]. In-document
 * `#anchors` stay inside it, ordinary HTTP/HTTPS links are handed to the system
 * browser, and *nothing* else is navigated to — relative Markdown links go through
 * `MarkViewBridge.openRelativeLink`, which the native layer validates with SAF.
 */
class MarkViewWebViewClient(
    private val assetLoader: MarkViewAssetLoader,
    private val callbacks: Callbacks,
) : WebViewClient() {

    interface Callbacks {
        fun onExternalUrl(url: String)
        fun onBlockedNavigation(url: String)
        fun onPageReady()
        fun onPageLoadFailed(url: String?, description: String?)
        fun onRendererGone()
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        return when (url.scheme?.lowercase(Locale.ROOT)) {
            "https", "http" -> assetLoader.shouldInterceptRequest(url)
            // Only the two URL spaces above are reachable; everything that could
            // touch the file system or another app is refused outright.
            "file", "content", "intent", "javascript" -> blockedResponse()
            // data: and anything else unknown is left to the engine (and to CSP).
            else -> null
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handleNavigation(request.url)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        handleNavigation(url.toUri())


    override fun onPageFinished(view: WebView, url: String) {
        callbacks.onPageReady()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) {
            callbacks.onPageLoadFailed(
                request.url?.toString(),
                error.description?.toString(),
            )
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (request.isForMainFrame) {
            callbacks.onPageLoadFailed(
                request.url?.toString(),
                "HTTP ${errorResponse.statusCode}",
            )
        }
    }

    /**
     * A WebView renderer crash must not take the app down; the controller rebuilds
     * the WebView instead (project book §9: exceptions must never crash MarkView).
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        callbacks.onRendererGone()
        return true
    }

    private fun handleNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)

        if (scheme == "https" && host == MarkViewAssetLoader.HOST) {
            // Allow the viewer page itself (including in-document #anchors), block any
            // attempt to navigate to another asset path.
            return uri.path != INDEX_PATH
        }

        if (scheme == "http" || scheme == "https") {
            callbacks.onExternalUrl(uri.toString())
            return true
        }

        callbacks.onBlockedNavigation(uri.toString())
        return true
    }

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Forbidden",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0)),
    )

    private companion object {
        const val INDEX_PATH = "/assets/markview/index.html"
    }
}
