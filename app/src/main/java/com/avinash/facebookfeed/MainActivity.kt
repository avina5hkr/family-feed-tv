package com.avinash.facebookfeed

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.File

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var webViewDestroyed = false

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val isDebug by lazy { applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }

    private val injectScript by lazy {
        assets.open("inject.js").bufferedReader().use { it.readText() }
            .replace("__PAGE_ZOOM_PERCENT__", PAGE_ZOOM_PERCENT.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (isDebug) WebView.setWebContentsDebuggingEnabled(true)

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        configureWebView()
        logWebViewVersion()
        trimCacheIfNeeded()

        webView.loadUrl(HOME_URL)
        webView.requestFocus()
    }

    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            userAgentString = WebSettings.getDefaultUserAgent(this@MainActivity).replace("; wv", "")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = FeedWebViewClient()
        webView.webChromeClient = FeedChromeClient()
    }

    private fun logWebViewVersion() {
        if (!isDebug) return
        val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WebView.getCurrentWebViewPackage() else null
        Log.i(TAG, "WebView ${pkg?.packageName} ${pkg?.versionName}")
        Log.i(TAG, "User agent: ${webView.settings.userAgentString}")
    }

    /** Clears the WebView disk cache when it grows past [CACHE_LIMIT_BYTES]. Cookies and localStorage are not touched. */
    private fun trimCacheIfNeeded() {
        val dir = cacheDir
        Thread {
            val size = folderSize(dir)
            if (isDebug) Log.i(TAG, "Cache folder: ${size / 1024 / 1024} MB")
            if (size > CACHE_LIMIT_BYTES) {
                runOnUiThread {
                    if (!webViewDestroyed) {
                        webView.clearCache(true)
                        if (isDebug) Log.i(TAG, "Cache cleared")
                    }
                }
            }
        }.start()
    }

    private fun folderSize(dir: File): Long =
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isDebug) {
            val action = if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"
            Log.d(TAG, "Key ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode}) $action repeat=${event.repeatCount}")
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) handleBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Back: close fullscreen video, then go back in history, and exit only when there is no history left. */
    private fun handleBack() {
        when {
            customView != null -> hideCustomView()
            !webViewDestroyed && webView.canGoBack() -> webView.goBack()
            else -> finish()
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        (window.decorView as FrameLayout).removeView(view)
        customView = null
        webView.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (!webViewDestroyed) {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onPause() {
        if (!webViewDestroyed) {
            webView.onPause()
            webView.pauseTimers()
        }
        super.onPause()
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        trimCacheIfNeeded()
        super.onStop()
    }

    override fun onDestroy() {
        destroyWebView()
        super.onDestroy()
    }

    private fun destroyWebView() {
        if (webViewDestroyed) return
        webViewDestroyed = true
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }

    private fun inject(view: WebView) {
        view.evaluateJavascript(injectScript, null)
    }

    private inner class FeedWebViewClient : WebViewClient() {

        // Facebook is a single-page app: inject on full loads and on in-page history changes.
        // The script guards itself, so running it several times per page is safe.
        override fun onPageCommitVisible(view: WebView, url: String) = inject(view)

        override fun onPageFinished(view: WebView, url: String) = inject(view)

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = inject(view)

        /** Keep http/https inside the WebView; silently drop app schemes such as intent:, fb: and market:. */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            val allowed = scheme in WEB_SCHEMES
            if (!allowed && isDebug) Log.d(TAG, "Blocked navigation to ${request.url}")
            return !allowed
        }

        /** The renderer crashed or was killed for memory. Rebuild the Activity instead of letting the app crash. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "WebView renderer gone (crashed=${detail.didCrash()}), recreating")
            customView?.let { (window.decorView as FrameLayout).removeView(it) }
            customView = null
            customViewCallback = null
            destroyWebView()
            recreate()
            return true
        }
    }

    private inner class FeedChromeClient : WebChromeClient() {

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            (window.decorView as FrameLayout).addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            webView.visibility = View.GONE
            view.requestFocus()
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
    }

    companion object {
        private const val TAG = "FacebookFeed"
        private const val HOME_URL = "https://m.facebook.com/"

        /** Page zoom in percent. Tune this on the TV. */
        private const val PAGE_ZOOM_PERCENT = 150

        private const val CACHE_LIMIT_BYTES = 50L * 1024 * 1024
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")
    }
}
