package com.markview.viewer

import androidx.core.net.toUri
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.markview.viewer.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The single Activity (project book §3, §4, §8).
 *
 * It owns nothing but wiring: the home page is [HomeController], the viewer page is
 * [ViewerController], and the document stack behind the system back button is
 * [DocumentStack].
 */
class MainActivity : ComponentActivity(), HomeController.Host, ViewerController.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var folderStore: SafFolderStore
    private lateinit var documentResolver: SafDocumentResolver
    private lateinit var home: HomeController
    private lateinit var viewer: ViewerController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val documentStack = DocumentStack()
    /**
     * MV-07 bookkeeping. [recreated] marks an instance built by a configuration change: it
     * restores its document stack from `savedInstanceState` and must not consume the launch
     * intent again, because `handleIntent` would replace that stack. [launchIntentConsumed]
     * records whether this instance already handled the intent it was started with, which is
     * what the `onNewIntent` log reports.
     */
    private var recreated = false
    private var launchIntentConsumed = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            popDocument()
        }
    }

    private val openDocumentTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { onFolderTreePicked(it) }
        }

    private val pickDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onDocumentPicked(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()
        applySystemBarAppearance()

        onBackPressedDispatcher.addCallback(this, backCallback)

        folderStore = SafFolderStore(this)
        documentResolver = SafDocumentResolver(this)

        home = HomeController(this, binding.homePage, folderStore, scope, this)
        viewer = ViewerController(
            activity = this,
            webViewContainer = binding.viewerPage.viewerWebviewContainer,
            errorView = binding.viewerPage.viewerError,
            errorTitleView = binding.viewerPage.viewerErrorTitle,
            errorDetailView = binding.viewerPage.viewerErrorDetail,
            resolver = documentResolver,
            scope = scope,
            host = this,
        )

        restoreDocumentStack(savedInstanceState)
        home.refresh()

        if (documentStack.current != null) {
            showViewer()
            documentStack.current?.let { viewer.show(it) }
        } else {
            showHome()
        }

        recreated = savedInstanceState != null

        Log.d(
            TAG,
            "onCreate: entry=${if (recreated) "recreated" else "fresh"}" +
                " action=${intent?.action} type=${intent?.type} data=${intent?.data}" +
                " clipItems=${intent?.clipData?.itemCount ?: 0} restoredDocuments=${documentStack.size}"
        )

        // MV-07: the launch intent is consumed once per launch, not once per Activity instance.
        // A configuration change (dark mode, rotation, font scale) recreates the activity with
        // the *same* intent; handling it again would call openDocumentUri(replaceStack = true)
        // and throw away the document stack that was just restored from savedInstanceState.
        if (!recreated) {
            launchIntentConsumed = true
            handleIntent(intent, fromNewIntent = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // MV-07: a logcat record for every second entry point. `recreated` and
        // `launchIntentConsumed` are what tells "the activity was re-created" apart from
        // "the user really did start a new open" — the distinction the third-round report
        // could not make from the outside.
        Log.d(
            TAG,
            "onNewIntent: action=${intent.action} categories=${intent.categories}" +
                " hasData=${intent.data != null} hasClipData=${intent.clipData != null}" +
                " hasStream=${intent.hasExtra(Intent.EXTRA_STREAM)}" +
                " recreated=$recreated launchIntentConsumed=$launchIntentConsumed" +
                " documentsOnStack=${documentStack.size}"
        )
        handleIntent(intent, fromNewIntent = true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarAppearance()
        viewer.onConfigurationChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val snapshots = documentStack.snapshots()
        val array = JSONArray()
        for (snapshot in snapshots) {
            array.put(
                JSONObject()
                    .put(KEY_SNAPSHOT_URI, snapshot.uri)
                    .put(KEY_SNAPSHOT_NAME, snapshot.displayName)
                    .put(KEY_SNAPSHOT_MIME, snapshot.mimeType)
                    .put(KEY_SNAPSHOT_FROM_TREE, snapshot.fromAuthorizedTree)
                    .put(KEY_SNAPSHOT_TREE, snapshot.treeUri ?: JSONObject.NULL)
                    .put(KEY_SNAPSHOT_ANCESTORS, JSONArray(snapshot.ancestorDocumentIds))
            )
        }
        outState.putString(KEY_DOCUMENT_STACK, array.toString())
        outState.putInt(KEY_DOCUMENT_INDEX, documentStack.currentIndex)
    }

    override fun onDestroy() {
        viewer.destroy()
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------------- Home page

    override fun onAddFolderClicked() {
        try {
            openDocumentTree.launch(null)
        } catch (error: ActivityNotFoundException) {
            toast(getString(R.string.toast_no_file_manager))
        }
    }

    override fun onOpenFileClicked() {
        try {
            pickDocument.launch(MarkdownFileType.PICKER_MIME_TYPES)
        } catch (error: ActivityNotFoundException) {
            toast(getString(R.string.toast_no_file_manager))
        }
    }

    private fun onFolderTreePicked(treeUri: Uri) {
        // Persist read access only (project book §4.2).
        val persisted = runCatching {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        }.getOrDefault(false)
        if (!persisted) {
            toast(getString(R.string.toast_folder_permission_failed))
        }
        home.onFolderAuthorized(treeUri)
    }

    private fun onDocumentPicked(uri: Uri) {
        openDocumentUri(
            uri = uri,
            declaredMimeType = null,
            declaredName = null,
            replaceStack = true,
            entry = ENTRY_PICKER,
        )
    }

    // --------------------------------------------------------------- Viewing flow

    override fun onOpenExternalUrl(url: String) {
        if (!UrlPolicy.isHttpReference(url)) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (error: ActivityNotFoundException) {
            toast(getString(R.string.toast_no_file_manager))
        }
    }

    override fun onOpenRelativeReference(reference: String, source: DocumentContext) {
        if (!UrlPolicy.looksLikeMarkdownReference(reference, null, null)) return
        if (!source.canResolveRelative) {
            // The document did not come from an authorised tree, so its directory is
            // unknown; relative links are not promised to work (project book §5.3).
            toast(getString(R.string.toast_relative_unavailable))
            return
        }
        scope.launch {
            val target = documentResolver.resolveRelative(source, reference)
            if (target == null) {
                toast(getString(R.string.toast_unreadable))
                return@launch
            }
            val name = target.uri.lastPathSegment?.substringAfterLast('/')
            if (!MarkdownFileType.isSupported(name, target.mimeType)) {
                toast(getString(R.string.toast_not_markdown, name ?: reference))
                return@launch
            }
            openDocumentUri(
                uri = target.uri,
                declaredMimeType = target.mimeType,
                declaredName = name,
                replaceStack = false,
                entry = ENTRY_RELATIVE,
            )
        }
    }

    override fun onDiagnostic(message: String) {
        // Diagnostics stay in logcat; no user-facing error management UI.
        Log.d(TAG, message)
    }

    private fun handleIntent(intent: Intent?, fromNewIntent: Boolean) {
        when (val parsed = IntentParser.parse(intent)) {
            is IntentParser.Result.Open -> {
                val uri = parsed.uri
                if (!IntentParser.CONTENT_SCHEME.equals(uri.scheme, ignoreCase = true)) {
                    toast(getString(R.string.toast_content_uri_only))
                    return
                }
                // MV-14 (small change 1): the persisted grant is taken further down, once the
                // document is known to be Markdown and is not already covered by a tree grant.
                val entry = if (parsed.fromShare) ENTRY_SHARE else ENTRY_VIEW
                openDocumentUri(uri, parsed.mimeType, parsed.displayName, replaceStack = true, entry = entry)
            }
            IntentParser.Result.Rejected -> toast(getString(R.string.toast_single_file_only))
            IntentParser.Result.None ->
                // MV-07: only a share that really carried no usable file is worth a message.
                // Any other intent — most of all the launcher's ACTION_MAIN when MarkView is
                // already at the top of the stack — is not a failed open and stays silent.
                if (fromNewIntent && intent?.action == Intent.ACTION_SEND) {
                    toast(getString(R.string.toast_no_shared_file))
                }
        }
    }

    /**
     * The single entry point for every way a document can arrive: the in-app picker,
     * `ACTION_VIEW`, single-file `ACTION_SEND` and relative Markdown links. Keeping one
     * path is what makes the entries behave identically (project book §4.3, MV-01).
     */
    private fun openDocumentUri(
        uri: Uri,
        declaredMimeType: String?,
        declaredName: String?,
        replaceStack: Boolean,
        entry: String,
    ) {
        scope.launch {
            val document = documentResolver.resolve(uri, declaredMimeType, declaredName)
            if (document == null) {
                Log.d(TAG, "open($entry) failed: unreadable uri=$uri")
                toast(getString(R.string.toast_unreadable))
                return@launch
            }
            if (!MarkdownFileType.isSupported(document.displayName, document.mimeType)) {
                Log.d(TAG, "open($entry) rejected: ${document.displayName} / ${document.mimeType}")
                toast(getString(R.string.toast_not_markdown, document.displayName))
                return@launch
            }
            Log.d(
                TAG,
                "open($entry): uri=${document.uri} name=${document.displayName}" +
                    " mime=${document.mimeType} tree=${document.treeUri}" +
                    " chainDepth=${document.ancestorDocumentIds.size}" +
                    " fromTree=${document.fromAuthorizedTree} replaceStack=$replaceStack"
            )
            // MV-14 (small change 1): only a document that is really displayed *and* is read
            // through the provider that delivered it needs its own persisted grant — a document
            // inside an authorised tree is already covered by the tree grant. Taking it earlier,
            // before the file was even known to be Markdown, is what left a permission behind
            // for every image ever shared or picked into MarkView.
            if (needsOwnPersistableGrant(document.uri, document.treeUri)) {
                tryTakePersistablePermission(document.uri)
                Log.d(TAG, "persist grant: taken for ${document.uri}")
            }
            if (replaceStack) {
                // Documents dropped by this open lose their persisted grant (small change 3a).
                val dropped = documentStack.snapshots()
                documentStack.clear()
                documentStack.push(document)
                releaseLeftStackGrants(dropped)
            } else {
                documentStack.push(document)
            }
            showViewer()
            viewer.show(document)
        }
    }

    private fun popDocument() {
        // Captured before the pop so the document that leaves the stack can release its grant.
        val before = documentStack.snapshots()
        val previous = documentStack.pop()
        if (previous == null) {
            documentStack.clear()
            showHome()
        } else {
            viewer.show(previous)
        }
        releaseLeftStackGrants(before)
    }

    /**
     * MV-14 (small change 3a): a document that left the document stack has no reason to keep a
     * persisted read grant, so it is released here instead of accumulating in the system list.
     *
     * Documents still in the stack are never touched — that grant is what lets a re-created
     * activity read the document again — and a document reached through an authorised tree does
     * not need one of its own. Nothing is released on a configuration change, because the stack
     * survives it.
     */
    private fun releaseLeftStackGrants(before: List<DocumentSnapshot>) {
        if (before.isEmpty()) return
        val kept = HashSet<String>()
        for (snapshot in documentStack.snapshots()) kept.add(snapshot.uri)
        for (snapshot in before) {
            if (snapshot.uri in kept) continue
            val uri = snapshot.uri.toUri()
            if (DocumentsContract.isTreeUri(uri)) continue
            if (!needsOwnPersistableGrant(uri, snapshot.treeUri?.toUri())) continue
            releasePersistableGrant(uri)
        }
    }

    private fun releasePersistableGrant(uri: Uri) {
        val released = runCatching {
            contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)
        Log.d(TAG, "grant release: uri=$uri released=$released")
    }

    /**
     * True when the document is read through the provider that delivered it (a shared file, a
     * file manager's own FileProvider) rather than through an authorised tree, whose grant
     * already covers it. Identifiers are only comparable inside one provider, so "same
     * authority as the tree" is exactly the case the tree grant covers (see [TreePathPolicy]).
     */
    private fun needsOwnPersistableGrant(uri: Uri, treeUri: Uri?): Boolean {
        if (treeUri == null) return true
        return !TreePathPolicy.sameAuthority(uri.authority, treeUri.authority)
    }

    private fun tryTakePersistablePermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    // ------------------------------------------------------------------- chrome

    private fun showViewer() {
        binding.homePage.root.visibility = View.GONE
        binding.viewerPage.root.visibility = View.VISIBLE
        backCallback.isEnabled = true
    }

    private fun showHome() {
        binding.viewerPage.root.visibility = View.GONE
        binding.homePage.root.visibility = View.VISIBLE
        backCallback.isEnabled = false
    }

    /**
     * Status/navigation bar icons follow the theme. Done from code because
     * `android:windowLightNavigationBar` needs values-v27 and minSdk here is 26.
     */
    private fun applySystemBarAppearance() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        val darkIcons = !isNightMode()
        controller.isAppearanceLightStatusBars = darkIcons
        controller.isAppearanceLightNavigationBars = darkIcons
    }

    private fun isNightMode(): Boolean {
        val uiMode = resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun restoreDocumentStack(savedState: Bundle?) {
        val raw = savedState?.getString(KEY_DOCUMENT_STACK) ?: return
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return
        val snapshots = ArrayList<DocumentSnapshot>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val uri = item.optString(KEY_SNAPSHOT_URI, "")
            if (uri.isBlank()) continue
            val ancestors = item.optJSONArray(KEY_SNAPSHOT_ANCESTORS)
            val ancestorList = ArrayList<String>()
            if (ancestors != null) {
                for (position in 0 until ancestors.length()) {
                    ancestorList.add(ancestors.optString(position, ""))
                }
            }
            snapshots.add(
                DocumentSnapshot(
                    uri = uri,
                    displayName = item.optString(KEY_SNAPSHOT_NAME, ""),
                    mimeType = item.optString(KEY_SNAPSHOT_MIME, ""),
                    fromAuthorizedTree = item.optBoolean(KEY_SNAPSHOT_FROM_TREE, false),
                    treeUri = if (item.isNull(KEY_SNAPSHOT_TREE)) null else item.optString(KEY_SNAPSHOT_TREE),
                    ancestorDocumentIds = ancestorList,
                )
            )
        }
        documentStack.restore(snapshots, savedState.getInt(KEY_DOCUMENT_INDEX, -1))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "MarkView"
        const val ENTRY_PICKER = "picker"
        const val ENTRY_VIEW = "view"
        const val ENTRY_SHARE = "share"
        const val ENTRY_RELATIVE = "relative-link"
        const val KEY_DOCUMENT_STACK = "markview.documentStack"
        const val KEY_DOCUMENT_INDEX = "markview.documentIndex"
        const val KEY_SNAPSHOT_URI = "uri"
        const val KEY_SNAPSHOT_NAME = "name"
        const val KEY_SNAPSHOT_MIME = "mime"
        const val KEY_SNAPSHOT_FROM_TREE = "fromTree"
        const val KEY_SNAPSHOT_TREE = "tree"
        const val KEY_SNAPSHOT_ANCESTORS = "ancestors"
    }
}
