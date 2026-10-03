package com.markview.viewer

import androidx.core.net.toUri
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.markview.viewer.databinding.ItemAuthorizedFolderBinding
import com.markview.viewer.databinding.PageHomeBinding

/**
 * The home page (project book §4.1, §4.2).
 *
 * The list only ever reports authorisation state: rows are not clickable, a long
 * press removes the record *and* releases the persisted read permission, duplicates
 * are refused, and a record whose permission has died stays visible but marked
 * unavailable until the user removes it.
 */
class HomeController(
    private val activity: ComponentActivity,
    private val binding: PageHomeBinding,
    private val store: SafFolderStore,
    private val scope: CoroutineScope,
    private val host: Host,
) {

    interface Host {
        fun onAddFolderClicked()
        fun onOpenFileClicked()
    }

    private class RowModel(
        val treeUri: String,
        val name: String,
        val location: String,
        val available: Boolean,
    )

    private val inflater: LayoutInflater = LayoutInflater.from(activity)

    init {
        binding.actionAddFolder.setOnClickListener { host.onAddFolderClicked() }
        binding.actionOpenFile.setOnClickListener { host.onOpenFileClicked() }
    }

    /** Re-checks every stored folder and redraws the list. */
    fun refresh() {
        scope.launch {
            val models = withContext(Dispatchers.IO) {
                store.refreshAvailability().map { folder -> toRowModel(folder) }
            }
            render(models)
        }
    }

    /** Records a folder the user just authorised with `ACTION_OPEN_DOCUMENT_TREE`. */
    fun onFolderAuthorized(treeUri: Uri) {
        scope.launch {
            val name = withContext(Dispatchers.IO) { SafUris.folderName(activity, treeUri) }.orEmpty()
            val added = store.add(treeUri.toString(), name)
            toast(if (added) R.string.toast_folder_added else R.string.toast_folder_duplicate)
            refresh()
        }
    }

    private fun removeFolder(treeUri: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.releasePersistableUriPermission(
                        treeUri.toUri(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            store.remove(treeUri)
            toast(R.string.toast_folder_removed)
            refresh()
        }
    }

    private fun toRowModel(folder: SafFolderStore.AuthorizedFolder): RowModel {
        val uri = folder.treeUri.toUri()
        val name = folder.displayName.takeIf { it.isNotBlank() }
            ?: SafUris.folderName(activity, uri)
            ?: activity.getString(R.string.folder_name_fallback)
        val location = SafUris.locationLabel(uri)
            ?: activity.getString(R.string.folder_location_unavailable)
        return RowModel(folder.treeUri, name, location, folder.available)
    }

    private fun render(models: List<RowModel>) {
        binding.foldersContainer.removeAllViews()
        binding.homeEmpty.visibility = if (models.isEmpty()) View.VISIBLE else View.GONE

        for (model in models) {
            val row = ItemAuthorizedFolderBinding.inflate(inflater, binding.foldersContainer, false)
            row.folderName.text = model.name
            row.folderLocation.text = model.location
            row.folderStatus.visibility = if (model.available) View.GONE else View.VISIBLE
            row.folderStatus.setText(R.string.folder_status_unavailable)
            row.root.setBackgroundResource(
                if (model.available) {
                    R.drawable.folder_row_background
                } else {
                    R.drawable.folder_row_background_unavailable
                }
            )
            // Rows are deliberately not clickable; a long press removes the record.
            row.root.setOnLongClickListener {
                removeFolder(model.treeUri)
                true
            }
            binding.foldersContainer.addView(row.root)
        }
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(activity, messageRes, Toast.LENGTH_SHORT).show()
    }
}
