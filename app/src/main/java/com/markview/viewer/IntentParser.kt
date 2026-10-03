package com.markview.viewer

import androidx.core.net.toUri
import android.content.Intent
import android.net.Uri

/**
 * Turns `ACTION_VIEW` / `ACTION_SEND` intents into at most one document URI
 * (project book §4.4). Multi-file intents and text-only shares are refused rather
 * than guessed at.
 */
object IntentParser {

    sealed class Result {
        /** A single `content://` URI to open. */
        class Open(
            val uri: Uri,
            val mimeType: String?,
            val displayName: String?,
            val fromShare: Boolean = false,
        ) : Result()

        /** The intent was a MarkView-shaped request but cannot be served (e.g. multi-file). */
        object Rejected : Result()

        /** Not an open/share request, or nothing usable inside it. */
        object None : Result()
    }

    fun parse(intent: Intent?): Result {
        if (intent == null) return Result.None
        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data
                if (data == null) Result.None else Result.Open(data, intent.type, null, fromShare = false)
            }
            Intent.ACTION_SEND -> parseSend(intent)
            else -> Result.None
        }
    }

    @Suppress("DEPRECATION") // Bundle.get is the only accessor that cannot throw on a mismatched type.
    private fun parseSend(intent: Intent): Result {
        // Read the raw parcelable so that a bundle containing an ArrayList (several
        // shared files) is refused instead of blowing up on a class cast.
        val rawStream = intent.extras?.get(Intent.EXTRA_STREAM)
        when (rawStream) {
            null -> Unit
            is Uri -> return Result.Open(rawStream, intent.type, null, fromShare = true)
            is String -> {
                val parsed = rawStream.toUri()
                return if (parsed.scheme == CONTENT_SCHEME) {
                    Result.Open(parsed, intent.type, null, fromShare = true)
                } else {
                    Result.Rejected
                }
            }
            else -> return Result.Rejected
        }

        val clipData = intent.clipData ?: return Result.None
        if (clipData.itemCount != 1) return Result.Rejected
        val uri = clipData.getItemAt(0).uri ?: return Result.None
        return Result.Open(uri, intent.type, null, fromShare = true)
    }

    const val CONTENT_SCHEME = "content"
}
