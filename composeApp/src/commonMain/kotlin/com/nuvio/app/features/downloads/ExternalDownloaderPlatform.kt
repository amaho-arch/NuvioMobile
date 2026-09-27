package com.nuvio.app.features.downloads

/**
 * Fork: hand a stream URL to a dedicated download-manager app
 * (Gopeed/ADM-style) instead of the built-in engine.
 */
internal expect object ExternalDownloaderPlatform {
    /** True when a capable download-manager app is installed. */
    fun canHandle(): Boolean

    /** Button label, e.g. "Send to Gopeed". */
    fun targetLabel(): String

    /** Returns true when the handoff intent was launched. */
    fun sendDownloadUrl(url: String, title: String?): Boolean
}
