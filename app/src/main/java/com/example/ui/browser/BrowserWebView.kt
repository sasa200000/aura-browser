package com.example.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.ActiveDownload
import com.example.data.model.BrowserTab
import com.example.downloader.DownloadHelper
import com.example.security.SecurityEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

/**
 * Renders the WebView that belongs to [tab].
 *
 * Each behaviour here fixes a defect of the original single-WebView design:
 *  - the view comes from [TabWebViewPool], so a tab owns its own history and UA;
 *  - navigation state is pushed upward instead of read during composition;
 *  - HTTP is upgraded on every navigation, not only on the very first load;
 *  - TLS failures cancel the load and report what actually failed.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserWebView(
    tab: BrowserTab,
    navigationRequest: String?,
    adBlockerEnabled: Boolean,
    antiPhishingEnabled: Boolean,
    forceHttps: Boolean,
    controller: BrowserController,
    onNavigationHandled: () -> Unit,
    onPageStarted: (String) -> Unit,
    onPageFinished: (String, String?) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onTrackerBlocked: () -> Unit,
    onNavigationStateChanged: (Boolean, Boolean) -> Unit,
    onSecurityInfo: (String, Boolean) -> Unit,
    onUnsafeBlocked: (String) -> Unit,
    onDownloadStarted: (ActiveDownload) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val webView = remember(tab.id) {
        TabWebViewPool.obtain(context, tab.id).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#0B0E14"))
        }
    }

    // Give the controller the live view so the nav buttons act on reality.
    DisposableEffect(tab.id, webView) {
        controller.current = webView
        onDispose { if (controller.current === webView) controller.current = null }
    }

    LaunchedEffect(tab.id, tab.isDesktopMode, tab.isIncognito) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = !tab.isIncognito
        settings.databaseEnabled = !tab.isIncognito
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setGeolocationEnabled(false)
        settings.saveFormData = !tab.isIncognito
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = true
        settings.cacheMode = if (tab.isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        settings.userAgentString = if (tab.isDesktopMode) DESKTOP_USER_AGENT else null
        // Cookie acceptance is process-wide in Android, so it stays on and a private
        // tab instead starts from a cleared jar (see BrowserViewModel.newTab).
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    }

    BackHandler(enabled = tab.canGoBack) {
        if (webView.canGoBack()) webView.goBack()
    }

    LaunchedEffect(navigationRequest) {
        if (!navigationRequest.isNullOrBlank()) {
            val urlToLoad =
                if (forceHttps) SecurityEngine.upgradeToHttps(navigationRequest) else navigationRequest
            webView.loadUrl(urlToLoad)
            onNavigationHandled()
        }
    }

    DisposableEffect(tab.id, adBlockerEnabled, antiPhishingEnabled, forceHttps) {
        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString()
                if (adBlockerEnabled && SecurityEngine.shouldBlockUrl(reqUrl)) {
                    scope.launch(Dispatchers.Main) { onTrackerBlocked() }
                    return SecurityEngine.createEmptyBlockedResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }

            /** Screens and upgrades *every* top-level navigation, including in-page links. */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val raw = request?.url?.toString() ?: return false
                if (antiPhishingEnabled && SecurityEngine.isPotentiallyUnsafe(raw)) {
                    onUnsafeBlocked(raw)
                    return true
                }
                if (forceHttps && raw.startsWith("http://")) {
                    view?.loadUrl(SecurityEngine.upgradeToHttps(raw))
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { onPageStarted(it) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) {
                    onPageFinished(url, view?.title)
                    reportSecurity(view, url, onSecurityInfo)
                }
                view?.let { onNavigationStateChanged(it.canGoBack(), it.canGoForward()) }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                view?.let { onNavigationStateChanged(it.canGoBack(), it.canGoForward()) }
            }

            /** Certificate errors are never silently ignored. */
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.cancel()
                val host = error?.url?.substringAfter("://")?.substringBefore("/").orEmpty()
                onSecurityInfo(
                    if (host.isBlank()) "خطای گواهی امنیتی — بارگذاری متوقف شد"
                    else "گواهی نامعتبر برای $host — بارگذاری متوقف شد",
                    true
                )
                view?.loadUrl("about:blank")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                onProgressChanged(newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                val currentUrl = view?.url ?: ""
                onPageFinished(currentUrl, title)
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            DownloadHelper.startSystemDownload(
                context = context,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                scope = scope,
                onDownloadStarted = onDownloadStarted
            )
        }

        onDispose {
            // The view stays in the pool so its tab keeps its history; only closing
            // the tab destroys it (see TabWebViewPool.release).
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Reads the live TLS certificate of the displayed page. The original build printed
 * a hard-coded "TLS 1.3 / AES-256 Validated" line for every site; this reports the
 * real issuer and expiry, or states plainly that the connection is not encrypted.
 */
private fun reportSecurity(
    view: WebView?,
    url: String,
    onSecurityInfo: (String, Boolean) -> Unit
) {
    if (view == null) return
    if (url.startsWith("http://")) {
        onSecurityInfo("اتصال ناامن (HTTP) — بدون رمزنگاری", true)
        return
    }
    if (!url.startsWith("https://")) return

    val cert = try {
        view.certificate
    } catch (_: Exception) {
        null
    }
    onSecurityInfo(SecurityEngine.describeCertificate(cert, persian = true), cert == null)
}
