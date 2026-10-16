package com.example.data.model

import java.util.UUID

enum class AppLanguage(val code: String, val displayName: String) {
    FA("fa", "فارسی (Persian)"),
    EN("en", "English")
}

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val url: String = "about:blank",
    val title: String = "New Tab",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isIncognito: Boolean = false,
    val isDesktopMode: Boolean = false,
    val blockedTrackersOnPage: Int = 0,
    val isSecureHttps: Boolean = true,
    /**
     * Filled from the live TLS connection by the WebView layer; never a canned
     * marketing string. Empty until the first page in the tab has loaded.
     */
    val certIssuer: String = "",
    /** Set when the TLS handshake for this page failed or was rejected. */
    val hasSslError: Boolean = false,
    /** Set while the page stayed on plain HTTP because upgrading was impossible. */
    val isCleartext: Boolean = false
)

enum class DownloadStatus {
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class ActiveDownload(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val url: String,
    val progress: Int = 0, // 0 - 100
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING,
    val mimeType: String = "",
    val speedKbps: Float = 0f,
    val filePath: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
