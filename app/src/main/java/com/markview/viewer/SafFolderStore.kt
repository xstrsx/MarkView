package com.markview.viewer

import androidx.core.content.edit
import androidx.core.net.toUri
import android.content.Context
import android.content.SharedPreferences
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted list of authorised folders (project book §4.2).
 *
 * Backed by `SharedPreferences` (explicitly allowed by the project book) holding a
 * small JSON array — no database, no per-item files. Records are only ever added or
 * removed by explicit user action; a record whose permission has died is kept and
 * flagged so the user can still see it and remove it.
 */
class SafFolderStore(context: Context) {

    data class AuthorizedFolder(
        val treeUri: String,
        val displayName: String,
        val addedAt: Long,
        val available: Boolean,
    )

    private val appContext = context.applicationContext
    private val preferences: SharedPreferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun list(): List<AuthorizedFolder> {
        val raw = preferences.getString(KEY_FOLDERS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = ArrayList<AuthorizedFolder>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val treeUri = item.optString(KEY_FIELD_TREE_URI, "")
            if (treeUri.isBlank()) continue
            result.add(
                AuthorizedFolder(
                    treeUri = treeUri,
                    displayName = item.optString(KEY_FIELD_NAME, ""),
                    addedAt = item.optLong(KEY_FIELD_ADDED_AT, 0L),
                    available = item.optBoolean(KEY_FIELD_AVAILABLE, true),
                )
            )
        }
        return result
    }

    /** Adds a folder unless the same tree URI is already recorded. Returns `true` when added. */
    fun add(treeUri: String, displayName: String): Boolean {
        val existing = list()
        if (existing.any { it.treeUri == treeUri }) return false
        val updated = existing + AuthorizedFolder(
            treeUri = treeUri,
            displayName = displayName,
            addedAt = System.currentTimeMillis(),
            available = true,
        )
        persist(updated)
        return true
    }

    fun remove(treeUri: String) {
        persist(list().filterNot { it.treeUri == treeUri })
    }

    fun setAvailability(treeUri: String, available: Boolean) {
        val current = list()
        val index = current.indexOfFirst { it.treeUri == treeUri }
        if (index < 0) return
        if (current[index].available == available) return
        val updated = ArrayList(current)
        updated[index] = AuthorizedFolder(
            treeUri = current[index].treeUri,
            displayName = current[index].displayName,
            addedAt = current[index].addedAt,
            available = available,
        )
        persist(updated)
    }

    /**
     * Re-checks every record against `contentResolver.persistedUriPermissions` and a
     * readable `DocumentFile`, persisting any change of availability.
     */
    suspend fun refreshAvailability(): List<AuthorizedFolder> = withContext(Dispatchers.IO) {
        val persisted = runCatching {
            appContext.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri.toString() }
                .toSet()
        }.getOrDefault(emptySet())

        val current = list()
        val checked = current.map { folder ->
            val uri = folder.treeUri.toUri()
            val usable = folder.treeUri in persisted &&
                runCatching { DocumentFile.fromTreeUri(appContext, uri)?.canRead() == true }
                    .getOrDefault(false)
            AuthorizedFolder(folder.treeUri, folder.displayName, folder.addedAt, usable)
        }
        if (checked != current) persist(checked)
        checked
    }

    private fun persist(folders: List<AuthorizedFolder>) {
        val array = JSONArray()
        for (folder in folders) {
            array.put(
                JSONObject()
                    .put(KEY_FIELD_TREE_URI, folder.treeUri)
                    .put(KEY_FIELD_NAME, folder.displayName)
                    .put(KEY_FIELD_ADDED_AT, folder.addedAt)
                    .put(KEY_FIELD_AVAILABLE, folder.available)
            )
        }
        preferences.edit { putString(KEY_FOLDERS, array.toString()) }
    }

    private companion object {
        const val PREFERENCES_NAME = "markview_authorized_folders"
        const val KEY_FOLDERS = "folders"
        const val KEY_FIELD_TREE_URI = "treeUri"
        const val KEY_FIELD_NAME = "displayName"
        const val KEY_FIELD_ADDED_AT = "addedAt"
        const val KEY_FIELD_AVAILABLE = "available"
    }
}
