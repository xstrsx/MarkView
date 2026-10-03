package com.markview.viewer

import android.content.res.Configuration
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Owns the viewer page: the WebView, the single bundled HTML page and the bridge
 * between them.
 *
 * The controller never parses Markdown itself — it reads the document with SAF and
 * hands the raw text to `MarkView.render` in the page, where the bundled renderers
 * take over (project book §6). Failures are isolated: an unreadable document shows a
 * small message, and any single failing renderer keeps the rest of the document.
 */
class ViewerController(
    private val activity: ComponentActivity,
    private val webViewContainer: FrameLayout,
    private val errorView: View,
    private val errorTitleView: TextView,
    private val errorDetailView: TextView,
    private val resolver: SafDocumentResolver,
    private val scope: CoroutineScope,
    private val host: Host,
) {

    interface Host {
        fun onOpenExternalUrl(url: String)
        fun onOpenRelativeReference(reference: String, source: DocumentContext)
        fun onDiagnostic(message: String)
    }

    private val bridgeHost = object : MarkViewBridge.Host {
        override fun openExternalUrl(url: String) = host.onOpenExternalUrl(url)

        override fun openRelativeReference(reference: String, source: DocumentContext) =
            host.onOpenRelativeReference(reference, source)

        override fun onDiagnostic(message: String) = host.onDiagnostic(message)
    }

    private val pageCallbacks = object : MarkViewWebViewClient.Callbacks {
        override fun onExternalUrl(url: String) = host.onOpenExternalUrl(url)

        override fun onBlockedNavigation(url: String) = host.onDiagnostic("blocked navigation: $url")

        override fun onPageReady() {
            pageReady = true
            applyTheme()
            flushPendingRender()
        }

        override fun onPageLoadFailed(url: String?, description: String?) =
            host.onDiagnostic("viewer page failed to load: $url ($description)")

        override fun onRendererGone() {
            host.onDiagnostic("webview renderer gone; rebuilding")
            rebuildWebView()
        }
    }

    private val assetLoader = MarkViewAssetLoader(activity) { registriesForRequests() }
    private val bridge = MarkViewBridge(resolver, { currentDocument }, bridgeHost)
    private val webViewClient = MarkViewWebViewClient(assetLoader, pageCallbacks)

    private var webView: WebView? = null

    /** Read from the WebView's request threads as well as the main thread. */
    @Volatile
    private var currentDocument: DocumentContext? = null

    /**
     * The document that was visible before the current one. Its tokens stay resolvable
     * until a third document arrives, so a request that is still in flight while a new
     * document is being rendered cannot fail spuriously (MV-01).
     */
    private var previousDocument: DocumentContext? = null

    private fun registriesForRequests(): List<ResourceRegistry> {
        val current = currentDocument
        val previous = previousDocument
        return when {
            current == null -> emptyList()
            previous == null || previous === current -> listOf(current.registry)
            else -> listOf(current.registry, previous.registry)
        }
    }
    private var pageReady = false
    private var pendingText: String? = null
    private var generation = 0

    val document: DocumentContext? get() = currentDocument

    /** Loads [document] into the viewer page. */
    fun show(document: DocumentContext) {
        val previous = currentDocument
        if (previous !== document) {
            resolver.invalidateCache()
            if (previous != null) {
                // Keep the outgoing registry one generation longer instead of dropping
                // its tokens immediately.
                previousDocument?.registry?.clear()
                previousDocument = previous
            }
        }
        currentDocument = document
        Log.d(
            TAG,
            "show: uri=${document.uri} name=${document.displayName} mime=${document.mimeType}" +
                " tree=${document.treeUri} chainDepth=${document.ancestorDocumentIds.size}" +
                " fromTree=${document.fromAuthorizedTree}"
        )
        generation += 1
        val expected = generation
        pendingText = null
        hideError()
        ensureWebView()

        scope.launch {
            when (val result = resolver.readText(document.uri)) {
                is SafDocumentResolver.TextResult.Success -> {
                    if (expected != generation) return@launch
                    pendingText = result.text
                    flushPendingRender()
                }
                SafDocumentResolver.TextResult.TooLarge -> {
                    if (expected != generation) return@launch
                    showError(
                        activity.getString(R.string.viewer_error_title),
                        activity.getString(R.string.viewer_error_too_large),
                    )
                }
                SafDocumentResolver.TextResult.Failed -> {
                    if (expected != generation) return@launch
                    showError(activity.getString(R.string.viewer_error_title), document.displayName)
                }
            }
        }
    }

    /** Called after a configuration change (font scale, rotation, density). */
    fun onConfigurationChanged() {
        val view = webView ?: return
        WebViewFactory.applyTextZoom(view, activity)
        applyTheme()
        // A new system font scale can change how large the SMILES labels render, so the
        // drawing range is re-measured instead of staying at a stale size (MV-05/MV-06).
        if (pageReady) {
            view.evaluateJavascript("window.MarkView && window.MarkView.refit();", null)
        }
    }

    fun destroy() {
        currentDocument?.registry?.clear()
        previousDocument?.registry?.clear()
        currentDocument = null
        previousDocument = null
        val view = webView
        webView = null
        pageReady = false
        pendingText = null
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            runCatching { view.destroy() }
        }
    }

    // ------------------------------------------------------------------ internals

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val view = WebViewFactory.create(
            context = activity,
            bridge = bridge,
            client = webViewClient,
            backgroundColor = ContextCompat.getColor(activity, R.color.app_background),
        )
        pageReady = false
        webView = view
        webViewContainer.addView(view, 0)
        view.loadUrl(MarkViewAssetLoader.INDEX_URL)
        return view
    }

    private fun rebuildWebView() {
        val dead = webView
        webView = null
        pageReady = false
        if (dead != null) {
            (dead.parent as? ViewGroup)?.removeView(dead)
            runCatching { dead.destroy() }
        }
        ensureWebView()
    }

    private fun flushPendingRender() {
        val view = webView ?: return
        if (!pageReady) return
        val text = pendingText ?: return
        applyTheme()
        view.evaluateJavascript(renderScript(text), null)
    }

    private fun applyTheme() {
        val view = webView ?: return
        if (!pageReady) return
        val theme = if (isNightMode()) "dark" else "light"
        view.evaluateJavascript("window.MarkView && window.MarkView.setTheme('$theme');", null)
    }

    private fun showError(title: String, detail: String) {
        pendingText = null
        val view = webView
        if (pageReady && view != null) {
            errorView.visibility = View.GONE
            view.evaluateJavascript(messageScript(title, detail), null)
            return
        }
        errorTitleView.text = title
        errorDetailView.text = detail
        errorView.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorView.visibility = View.GONE
    }

    private fun isNightMode(): Boolean {
        val uiMode = activity.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun renderScript(text: String): String =
        "window.MarkView && window.MarkView.render(" + JSONObject.quote(text) + ");"

    private fun messageScript(title: String, detail: String): String =
        "window.MarkView && window.MarkView.showMessage(" +
            JSONObject.quote(title) + "," + JSONObject.quote(detail) + ");"

    private companion object {
        const val TAG = "MarkView"
    }
}
