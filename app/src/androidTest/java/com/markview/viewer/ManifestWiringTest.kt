package com.markview.viewer

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the merged manifest really exposes MarkView for the three entry
 * points required by project book §4 (home page, "open with", single-file share).
 *
 * Instrumentation tests are not part of the pre-delivery gate (the project book only
 * requires `test`, `lint` and `assembleRelease`); they are kept as executable
 * documentation of the intent-filter wiring and run when a device is available with
 * `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ManifestWiringTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mainActivity =
        ComponentName(context.packageName, MainActivity::class.java.name)

    private fun targetsOf(intent: Intent): List<ComponentName> =
        context.packageManager.queryIntentActivities(intent, 0).map {
            ComponentName(it.activityInfo.packageName, it.activityInfo.name)
        }

    @Test
    fun launcherEntryPointExists() {
        assertNotNull(
            "the launcher intent must resolve for ${context.packageName}",
            context.packageManager.getLaunchIntentForPackage(context.packageName),
        )
    }

    @Test
    fun openWithResolvesForMarkdownContentUris() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://provider.test/document/1"))
            .setType("text/markdown")
        assertTrue(targetsOf(intent).contains(mainActivity))
    }

    @Test
    fun openWithResolvesForPlainTextContentUris() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://provider.test/document/2"))
            .setType("text/plain")
        assertTrue(targetsOf(intent).contains(mainActivity))
    }

    @Test
    fun openWithIgnoresUnrelatedMimeTypes() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://provider.test/document/3"))
            .setType("image/png")
        assertTrue(!targetsOf(intent).contains(mainActivity))
    }

    @Test
    fun shareResolvesForMarkdownMimeTypes() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/x-markdown")
        assertTrue(targetsOf(intent).contains(mainActivity))
    }

    @Test
    fun shareIgnoresUnrelatedMimeTypes() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/zip")
        assertTrue(!targetsOf(intent).contains(mainActivity))
    }
}
