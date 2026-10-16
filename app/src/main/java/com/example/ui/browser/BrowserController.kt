package com.example.ui.browser

import android.content.Context
import android.webkit.WebView

/**
 * Keeps one WebView per tab id.
 *
 * The original app had a single WebView shared by every tab, so back/forward
 * history and the user agent leaked across tabs. A view is destroyed only when
 * its tab is closed - which is also what preserves that tab's state while you
 * switch to another one.
 */
object TabWebViewPool {

    private val views = HashMap<String, WebView>()

    fun obtain(context: Context, tabId: String): WebView {
        views[tabId]?.let { return it }
        val view = WebView(context.applicationContext)
        views[tabId] = view
        return view
    }

    fun get(tabId: String): WebView? = views[tabId]

    fun release(tabId: String) {
        val view = views.remove(tabId) ?: return
        try {
            view.stopLoading()
            view.loadUrl("about:blank")
            view.removeAllViews()
            view.destroy()
        } catch (_: Exception) {
        }
    }

    fun releaseAll() {
        views.keys.toList().forEach { release(it) }
    }
}

/**
 * Handle the UI uses to drive the WebView currently on screen. Compose cannot read
 * WebView navigation state reactively, so the nav buttons act on the live view
 * instead of assuming what it can do.
 */
class BrowserController {

    internal var current: WebView? = null

    fun canGoBack(): Boolean = current?.canGoBack() ?: false

    fun canGoForward(): Boolean = current?.canGoForward() ?: false

    fun goBack(): Boolean {
        val view = current ?: return false
        return if (view.canGoBack()) {
            view.goBack()
            true
        } else {
            false
        }
    }

    fun goForward(): Boolean {
        val view = current ?: return false
        return if (view.canGoForward()) {
            view.goForward()
            true
        } else {
            false
        }
    }

    fun reload() {
        current?.reload()
    }

    fun stopLoading() {
        current?.stopLoading()
    }
}
