package com.nuvio.app.features.downloads

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Fork: hand stream URLs to Gopeed/ADM via VIEW/SEND intents.
 * The manager app downloads with its own fast engine; Nuvio does not
 * track those files (built-in downloads stay tracked as before).
 */
internal actual object ExternalDownloaderPlatform {
    private const val GOPEED_PACKAGE = "com.gopeed.gopeed"
    private const val ADM_PACKAGE = "com.dv.adm"

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun installedPackage(): String? {
        val context = appContext ?: return null
        return listOf(GOPEED_PACKAGE, ADM_PACKAGE).firstOrNull { pkg ->
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(pkg, 0)
                }
                true
            }.getOrDefault(false)
        }
    }

    actual fun canHandle(): Boolean = installedPackage() != null

    actual fun targetLabel(): String = when (installedPackage()) {
        GOPEED_PACKAGE -> "Send to Gopeed"
        ADM_PACKAGE -> "Send to ADM"
        else -> "Send to downloader"
    }

    actual fun sendDownloadUrl(url: String, title: String?): Boolean {
        val context = appContext ?: return false
        val trimmed = url.trim()
        if (trimmed.isBlank()) return false
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            ?.takeIf { it.scheme.equals("http", ignoreCase = true) || it.scheme.equals("https", ignoreCase = true) }
            ?: return false

        val target = installedPackage()
        val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (target != null) setPackage(target)
            if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_TITLE, title)
        }
        return try {
            context.startActivity(viewIntent)
            true
        } catch (_: ActivityNotFoundException) {
            if (target == null) return false
            // Target present but rejected VIEW: fall back to a generic share.
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, trimmed)
                if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(Intent.createChooser(sendIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        } catch (_: Throwable) {
            false
        }
    }
}
