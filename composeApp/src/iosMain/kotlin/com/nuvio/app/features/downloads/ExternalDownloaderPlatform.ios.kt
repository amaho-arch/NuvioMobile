package com.nuvio.app.features.downloads

/** Fork: no external download-manager handoff on iOS (Android fork feature). */
internal actual object ExternalDownloaderPlatform {
    actual fun canHandle(): Boolean = false

    actual fun targetLabel(): String = "Send to downloader"

    actual fun sendDownloadUrl(url: String, title: String?): Boolean = false
}
