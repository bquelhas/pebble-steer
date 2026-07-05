package com.bquelhas.steer

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands the watchapp bundled inside the APK (assets/steer.pbw) to the Pebble / Core Devices
 * app for installation, so a fresh watch build ships and installs together with the phone app.
 *
 * The asset is copied into the app's cache (exposed through [FileProvider]) and opened with a
 * VIEW intent — the same flow as tapping a .pbw file, which the Pebble app installs on the watch.
 */
object PbwInstaller {

    private const val ASSET = "steer.pbw"

    /** True when the APK actually shipped a bundled .pbw (it might not on a partial build). */
    fun isBundled(context: Context): Boolean =
        runCatching { context.assets.open(ASSET).use { it.read() >= 0 } }.getOrDefault(false)

    /**
     * Stages the bundled .pbw and launches an installer. Returns false if no app on the phone
     * can open a .pbw (i.e. the Pebble / Core Devices app isn't installed).
     */
    fun install(context: Context): Boolean {
        val staged = stage(context) ?: return false

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", staged
        )
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (view.resolveActivity(context.packageManager) == null) return false

        val chooser = Intent.createChooser(view, context.getString(R.string.pbw_install_chooser))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        return true
    }

    /** Copies assets/steer.pbw into cache/pbw/steer.pbw; null if the asset is missing. */
    private fun stage(context: Context): File? {
        val dir = File(context.cacheDir, "pbw").apply { mkdirs() }
        val out = File(dir, ASSET)
        return runCatching {
            context.assets.open(ASSET).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            out
        }.getOrNull()
    }
}
