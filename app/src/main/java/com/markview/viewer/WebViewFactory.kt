package com.markview.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlin.math.roundToInt

/**
 * Builds the viewer WebView with the strictest settings that still allow the
 * bundled page to work (project book §5.4, §7, §9).
 *
 * JavaScript is required because KaTeX, Mermaid and SMILES draw locally, but the
 * engine gets no access to the file system, no content-provider access, no file-URL
 * cross-origin access and no way to open popups. Local content only ever arrives
 * through [MarkViewAssetLoader].
 */
object WebViewFactory {

    const val BRIDGE_NAME = "MarkViewBridge"

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun create(
        context: Context,
        bridge: MarkViewBridge,
        client: WebViewClient,
        backgroundColor: Int,
    ): WebView {
        val webView = WebView(context)
        val settings = webView.settings

        // Local renderers only — see index.html for the matching CSP.
        settings.javaScriptEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)

        // No file system, no content providers, no file-URL escapes.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false

        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        settings.setGeolocationEnabled(false)
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true

        // Remote images referenced by the document must load; nothing else may.
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.blockNetworkLoads = false
        // The page is served over https://appassets..., so plain http images need the
        // compatibility mode. This is the only relaxation made.
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // MarkView ships its own light/dark palette; forced darkening would
            // break it.
            runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false) }
        }
        settings.textZoom = textZoomPercent(context)

        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        webView.setBackgroundColor(backgroundColor)

        webView.webViewClient = client
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)
        return webView
    }

    /** System font scale → WebView text zoom, so body text follows the user setting. */
    fun textZoomPercent(context: Context): Int =
        (context.resources.configuration.fontScale * 100f).roundToInt().coerceIn(50, 300)

    fun applyTextZoom(webView: WebView, context: Context) {
        val zoom = textZoomPercent(context)
        if (webView.settings.textZoom != zoom) {
            webView.settings.textZoom = zoom
        }
    }

}
