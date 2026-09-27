package com.nuvio.app.features.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Fork: public download location.
 *
 * Completed downloads live in /sdcard/Movies/Nuvio so file managers, USB/MTP
 * and external players (VLC/MPV) can see them. Internal bookkeeping
 * (transfer store) stays in private storage.
 */
internal object NuvioPublicDownloads {
    const val RELATIVE_DIR = "Movies/Nuvio"

    fun hasAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return Environment.isExternalStorageManager()
    }

    /** Public dir, or null when unavailable (permission missing / storage gone). */
    fun directory(): File? {
        val state = Environment.getExternalStorageState()
        if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return null
        val dir = File(Environment.getExternalStorageDirectory(), RELATIVE_DIR)
        return if (dir.isDirectory || dir.mkdirs()) dir else null
    }

    /** Sends the user to this app's "All files access" settings page. */
    fun openAccessSettings(context: Context): Boolean {
        val intents = listOf(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        return intents.any { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}
