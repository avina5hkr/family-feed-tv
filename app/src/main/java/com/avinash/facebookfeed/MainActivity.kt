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
            // Lets the injected viewport meta set any page zoom, including below 100%.
            useWideViewPort = true
            userAgentString = desktopUserAgent(WebSettings.getDefaultUserAgent(this@MainActivity))
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = FeedWebViewClient()
        webView.webChromeClient = FeedChromeClient()
    }

    /**
     * Facebook serves its phone site (stretched layout, "Open app" prompts) to any Android user agent,
     * so present as desktop Chrome on Linux. The Chrome major version comes from the installed WebView.
     */
    private fun desktopUserAgent(defaultUserAgent: String): String {
        val major = Regex("""Chrome/(\d+)""").find(defaultUserAgent)?.groupValues?.get(1)
            ?: return defaultUserAgent.replace("; wv", "")
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
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
        if (event.keyCode == KeyEvent.KEYCODE_0) {
            // 0: reload the page, in case Facebook gets stuck.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !webViewDestroyed) webView.reload()
            return true
        }
        val command = REMOTE_COMMANDS[event.keyCode]
        if (command != null) {
            if (event.action == KeyEvent.ACTION_DOWN && !webViewDestroyed) {
                // Held Channel/Page keys repeat: keep scrolling for as long as the repeats come.
                val name = when {
                    event.repeatCount == 0 -> command
                    command == "pageDown" -> "holdDown"
                    command == "pageUp" -> "holdUp"
                    else -> null
                }
                if (name != null) webView.evaluateJavascript("window.__fbtv && window.__fbtv.command('$name')", null)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Back: close fullscreen video, then let the page close a dialog or leave a text field (or jump to the top of
     * a scrolled feed), then go back in history, and exit only when there is no history left.
     */
    private fun handleBack() {
        if (customView != null) {
            hideCustomView()
            return
        }
        if (webViewDestroyed) {
            finish()
            return
        }
        webView.evaluateJavascript("window.__fbtv ? window.__fbtv.back() : 'none'") { result ->
            if (webViewDestroyed) return@evaluateJavascript
            when (result?.trim('"')) {
                "handled" -> Unit
                "reels" -> goBackPast { isReelUrl(it) }
                else -> goBackPast { false }
            }
        }
    }

    /** Goes back to the newest history entry that [skip] rejects; every reel watched is its own entry. */
    private fun goBackPast(skip: (String) -> Boolean) {
        val history = webView.copyBackForwardList()
        var index = history.currentIndex - 1
        while (index >= 0 && skip(history.getItemAtIndex(index).url)) index--
        if (index >= 0) webView.goBackOrForward(index - history.currentIndex) else finish()
    }

    private fun isReelUrl(url: String): Boolean =
        android.net.Uri.parse(url).path?.let { it.startsWith("/reel/") || it.startsWith("/reels/") } == true

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

        /**
         * Keep http/https inside the WebView; silently drop app schemes such as intent:, fb: and market:.
         * Links to the phone site (m.facebook.com and friends) are rewritten to www.facebook.com.
         */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase()
            if (scheme !in WEB_SCHEMES) {
                if (isDebug) Log.d(TAG, "Blocked navigation to $url")
                return true
            }
            if (request.isForMainFrame && url.host?.lowercase() in PHONE_SITE_HOSTS) {
                val desktopUrl = url.buildUpon().scheme("https").authority(DESKTOP_HOST).build().toString()
                if (isDebug) Log.d(TAG, "Rewrote $url to $desktopUrl")
                view.loadUrl(desktopUrl)
                return true
            }
            return false
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
        private const val DESKTOP_HOST = "www.facebook.com"
        private const val HOME_URL = "https://$DESKTOP_HOST/"
        private val PHONE_SITE_HOSTS = setOf("m.facebook.com", "mobile.facebook.com", "touch.facebook.com", "mbasic.facebook.com")

        /** Page zoom in percent: 125 lays the page out 768 CSS px wide on a 1080p TV (100 = 960). Tune this on the TV. */
        private const val PAGE_ZOOM_PERCENT = 125

        private const val CACHE_LIMIT_BYTES = 50L * 1024 * 1024

        /**
         * Extra remote keys (TCL RC833), handled only inside this app, mapped to window.__fbtv.command() names.
         * Channel -/+ (and Page Down/Up): scroll a screen down/up, or next/previous reel in Reels; held down,
         * they scroll continuously.
         * Red: Home feed, Green: Reels, Yellow: Like, Blue: Comments, Menu: top bar.
         */
        private val REMOTE_COMMANDS = mapOf(
            KeyEvent.KEYCODE_CHANNEL_DOWN to "pageDown",
            KeyEvent.KEYCODE_CHANNEL_UP to "pageUp",
            KeyEvent.KEYCODE_PAGE_DOWN to "pageDown",
            KeyEvent.KEYCODE_PAGE_UP to "pageUp",
            KeyEvent.KEYCODE_PROG_RED to "home",
            KeyEvent.KEYCODE_PROG_GREEN to "reels",
            KeyEvent.KEYCODE_PROG_YELLOW to "like",
            KeyEvent.KEYCODE_PROG_BLUE to "comment",
            KeyEvent.KEYCODE_MENU to "topBar",
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "toggle",
            KeyEvent.KEYCODE_MEDIA_PLAY to "play",
            KeyEvent.KEYCODE_MEDIA_PAUSE to "pause",
            KeyEvent.KEYCODE_MEDIA_REWIND to "rewind",
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to "forward",
        )
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")
    }
}
