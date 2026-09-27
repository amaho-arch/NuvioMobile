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

    actual fun sendDownloadUrl(url: String, title: String?, fileName: String?, relativeDir: String?): Boolean {
        val context = appContext ?: return false
        val trimmed = url.trim()
        if (trimmed.isBlank()) return false
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return false
        }

        // Fork: Gopeed scheme protocol creates the task directly with our
        // uniform name + folder, no chooser, no manual steps.
        // See https://gopeed.com/docs/scheme
        if (installedPackage() == GOPEED_PACKAGE) {
            buildGopeedCreateUri(trimmed, fileName, relativeDir)?.let { gopeedUri ->
                val schemeIntent = Intent(Intent.ACTION_VIEW, gopeedUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage(GOPEED_PACKAGE)
                }
                if (runCatching {
                        context.startActivity(schemeIntent)
                        true
                    }.getOrDefault(false)) return true
            }
        }
        return sendGenericUrl(context, trimmed, title)
    }

    private fun buildGopeedCreateUri(url: String, fileName: String?, relativeDir: String?): Uri? {
        return runCatching {
            val opt = org.json.JSONObject()
            if (!fileName.isNullOrBlank()) opt.put("name", fileName)
            val dir = relativeDir?.trim()?.trim('/')
            if (!dir.isNullOrBlank()) {
                // Gopeed expects an absolute folder; ours lives under Movies.
                opt.put("path", "/sdcard/Movies/Nuvio/$dir")
            }
            val payload = org.json.JSONObject()
                .put("req", org.json.JSONObject().put("url", url))
                .put("opt", opt)
            val encoded = android.util.Base64.encodeToString(
                payload.toString().toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
            )
            Uri.parse("gopeed:///create?params=$encoded")
        }.getOrNull()
    }

    private fun sendGenericUrl(context: Context, trimmed: String, title: String?): Boolean {
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return false
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
