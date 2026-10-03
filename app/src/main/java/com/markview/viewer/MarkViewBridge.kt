package com.markview.viewer

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * The `JavaScriptInterface` the viewer page talks to.
 *
 * It is deliberately tiny and every entry point is validated natively:
 *
 *  - [resolveResource] only ever answers with a tokenised URL for a resource that
 *    SAF resolution found *inside* the current document's authorised tree, and only
 *    when that resource is an image;
 *  - [openExternal] and [openRelativeLink] are forwarded to the main thread and
 *    re-validated there (never trusted as given);
 *  - [reportMessage] is diagnostics only.
 *
 * Note: `@JavascriptInterface` methods are invoked on the WebView's private Java
 * bridge thread, not on the UI thread, so the blocking SAF lookup below cannot
 * stall the interface.
 */
class MarkViewBridge(
    private val resolver: SafDocumentResolver,
    private val contextProvider: () -> DocumentContext?,
    private val host: Host,
) {

    interface Host {
        fun openExternalUrl(url: String)
        fun openRelativeReference(reference: String, source: DocumentContext)
        fun onDiagnostic(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Resolves a Markdown-relative image reference to a bridgeable URL.
     *
     * Each decision is logged (identifier + MIME only, never document content) so that a
     * real-device report can name the failing stage instead of guessing (MV-01).
     */
    @JavascriptInterface
    fun resolveResource(rawSource: String): String {
        val document = contextProvider() ?: run {
            Log.d(TAG, "resource '$rawSource' rejected: no current document")
            return errorJson("no-document")
        }
        val reference = rawSource.trim()
        if (reference.isEmpty() || reference.length > MAX_REFERENCE_LENGTH) {
            Log.d(TAG, "resource '$rawSource' rejected: invalid reference")
            return errorJson("invalid-reference")
        }
        if (UrlPolicy.isHttpReference(reference) || UrlPolicy.hasExplicitScheme(reference)) {
            Log.d(TAG, "resource '$reference' rejected: not a relative reference")
            return errorJson("external-reference")
        }

        val target = try {
            runBlocking { resolver.resolveRelative(document, reference) }
        } catch (failure: Exception) {
            Log.d(TAG, "resource '$reference' failed during resolution (${failure.message})")
            return errorJson("resolve-failed")
        } ?: run {
            Log.d(TAG, "resource '$reference' not found under the authorised tree")
            return errorJson("not-found")
        }

        if (!MimeTypes.isImage(target.mimeType)) {
            Log.d(TAG, "resource '$reference' rejected: ${target.mimeType} is not an image")
            return errorJson("unsupported-type")
        }

        val token = document.registry.register(target.uri, target.mimeType)
        Log.d(TAG, "resource '$reference' -> token=$token mime=${target.mimeType} uri=${target.uri}")
        return runCatching {
            JSONObject().put("url", MarkViewAssetLoader.RESOURCE_URL_PREFIX + token).toString()
        }.getOrDefault(errorJson("registry-failed"))
    }

    @JavascriptInterface
    fun openExternal(url: String) {
        if (url.isBlank() || url.length > MAX_URL_LENGTH) return
        mainHandler.post { host.openExternalUrl(url) }
    }

    @JavascriptInterface
    fun openRelativeLink(rawHref: String) {
        val reference = rawHref.trim()
        if (reference.isEmpty() || reference.length > MAX_REFERENCE_LENGTH) return
        val document = contextProvider() ?: return
        mainHandler.post { host.openRelativeReference(reference, document) }
    }

    @JavascriptInterface
    fun reportMessage(message: String) {
        val text = message.take(MAX_MESSAGE_LENGTH)
        mainHandler.post { host.onDiagnostic(text) }
    }

    private fun errorJson(reason: String): String =
        runCatching { JSONObject().put("error", reason).toString() }.getOrDefault("{}")

    private companion object {
        const val TAG = "MarkView"
        const val MAX_REFERENCE_LENGTH = 2048
        const val MAX_URL_LENGTH = 8192
        const val MAX_MESSAGE_LENGTH = 512
    }
}
