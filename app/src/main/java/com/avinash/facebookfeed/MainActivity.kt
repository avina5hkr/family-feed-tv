package com.avinash.facebookfeed

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
import android.widget.TextView
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
            .replace("__DEBUG__", isDebug.toString())
            .replace("__REELS_CAPTION_SCALE_PERCENT__", REELS_CAPTION_SCALE_PERCENT.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (isDebug) WebView.setWebContentsDebuggingEnabled(true)

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        addPointerViews()
        configureWebView()
        logWebViewVersion()
        trimCacheIfNeeded()

        webView.loadUrl(debugUrl(intent) ?: HOME_URL)
        webView.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        debugUrl(intent)?.let { if (!webViewDestroyed) webView.loadUrl(it) }
    }

    /**
     * Debug builds only: `adb shell am start -n com.avinash.facebookfeed.debug/com.avinash.facebookfeed.MainActivity
     * --es url https://...` opens that page, for testing pages Facebook shows only sometimes. https only.
     */
    private fun debugUrl(intent: Intent?): String? {
        if (!BuildConfig.DEBUG) return null
        val url = intent?.getStringExtra(EXTRA_URL) ?: return null
        if (Uri.parse(url).scheme?.lowercase() != "https") {
            Log.w(TAG, "Ignored url extra (https only): $url")
            return null
        }
        Log.i(TAG, "Opening url extra: $url")
        return url
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
        if (event.keyCode == POINTER_TOGGLE_KEY) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !webViewDestroyed) {
                if (pointerOn) leavePointerMode() else setPointerMode(true, manual = true)
            }
            return true
        }
        if (pointerOn && handlePointerKey(event)) return true
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
            exitApp()
            return
        }
        if (pointerOn) {
            leavePointerMode()
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
        if (index >= 0) webView.goBackOrForward(index - history.currentIndex) else exitApp()
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

    // ---------- Pointer mode ----------
    //
    // A CAPTCHA runs in a cross-origin frame that inject.js can't reach, and keyboard focus inside it is invisible.
    // Pointer mode draws a pointer above the WebView instead: the arrows move it and OK taps where it points, as a
    // real touch event, so every choice is still made by the person holding the remote. It turns on by itself on
    // Facebook's login and check pages and while a CAPTCHA frame is on screen, and with POINTER_TOGGLE_KEY anywhere.

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pointerView: PointerView
    private lateinit var pointerBanner: TextView
    private var pointerOn = false
    private var pointerX = -1f
    private var pointerY = -1f
    /** Turned on with the toggle key: stays on until Back or the toggle key, whatever the page. */
    private var pointerManual = false
    /** The page wants pointer mode, from the last check. */
    private var pointerWanted = false
    /** Turned off by hand on a page that wants it: stays off until the page stops wanting it. */
    private var pointerDeclined = false

    private val pointerCheck = object : Runnable {
        override fun run() {
            checkPointerWanted()
            handler.postDelayed(this, POINTER_CHECK_MS)
        }
    }

    private fun addPointerViews() {
        val content = findViewById<FrameLayout>(android.R.id.content)
        val density = resources.displayMetrics.density
        pointerBanner = TextView(this).apply {
            text = getString(R.string.pointer_banner)
            setTextColor(Color.WHITE)
            textSize = 13f
            val pad = (12 * density).toInt()
            setPadding(2 * pad, pad, 2 * pad, pad)
            background = GradientDrawable().apply {
                setColor(0xE6202020.toInt())
                cornerRadius = 12 * density
            }
            isFocusable = false
            visibility = View.GONE
        }
        content.addView(pointerBanner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            // Top right: CAPTCHA boxes and Facebook's login form sit in the middle or on the left.
            Gravity.TOP or Gravity.END
        ).apply { topMargin = (16 * density).toInt(); marginEnd = (16 * density).toInt() })
        // Added last, so it is drawn above the banner too.
        pointerView = PointerView(this).apply { visibility = View.GONE }
        content.addView(pointerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Asks the page whether a CAPTCHA frame is on screen (and tells it whether pointer mode is on). */
    private fun checkPointerWanted() {
        if (webViewDestroyed) return
        val onCheckPage = isLoginOrCheckPage(webView.url)
        webView.evaluateJavascript("window.__fbtv ? window.__fbtv.pointer($pointerOn) : false") { result ->
            if (webViewDestroyed) return@evaluateJavascript
            val wanted = onCheckPage || result == "true"
            if (wanted != pointerWanted && isDebug) {
                // Host and path only: Facebook's login URLs carry tokens in the query.
                val page = webView.url?.let(Uri::parse)?.let { "${it.host}${it.path}" }
                Log.i(TAG, "Pointer wanted: $wanted ($page)")
            }
            pointerWanted = wanted
            if (!wanted) {
                pointerDeclined = false
                if (pointerOn && !pointerManual) setPointerMode(false)
            } else if (!pointerOn && !pointerDeclined) {
                setPointerMode(true)
            }
        }
    }

    private fun isLoginOrCheckPage(url: String?): Boolean {
        val uri = url?.let(Uri::parse) ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host != "facebook.com" && !host.endsWith(".facebook.com")) return false
        return LOGIN_PATH.containsMatchIn(uri.path ?: "")
    }

    private fun setPointerMode(on: Boolean, manual: Boolean = false) {
        pointerOn = on
        pointerManual = on && manual
        if (on) pointerDeclined = false
        if (on && pointerX < 0) {
            pointerX = webView.width / 2f
            pointerY = webView.height / 2f
        }
        placePointer()
        pointerView.visibility = if (on) View.VISIBLE else View.GONE
        pointerBanner.visibility = if (on) View.VISIBLE else View.GONE
        if (!webViewDestroyed) webView.evaluateJavascript("window.__fbtv && window.__fbtv.pointer($on)", null)
        if (isDebug) Log.i(TAG, "Pointer mode ${if (on) "on" else "off"}${if (manual) " (toggle key)" else ""}")
    }

    /** Back or the toggle key: off, and it stays off on this page even if the page wants it. */
    private fun leavePointerMode() {
        setPointerMode(false)
        pointerDeclined = pointerWanted
    }

    private fun handlePointerKey(event: KeyEvent): Boolean {
        val (dx, dy) = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && !webViewDestroyed) tapAtPointer()
                return true
            }
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && !webViewDestroyed) movePointer(dx, dy, event.repeatCount)
        return true
    }

    /** Moves the pointer, faster the longer a key is held. Pushed past the top or bottom edge, it scrolls instead. */
    private fun movePointer(dx: Int, dy: Int, repeatCount: Int) {
        val step = (POINTER_STEP_PX + repeatCount * POINTER_ACCEL_PX).coerceAtMost(POINTER_MAX_STEP_PX)
        val maxX = webView.width - 1f
        val maxY = webView.height - 1f
        if ((dy < 0 && pointerY <= 0f) || (dy > 0 && pointerY >= maxY)) {
            scrollAtPointer(dy)
            return
        }
        pointerX = (pointerX + dx * step).coerceIn(0f, maxX)
        pointerY = (pointerY + dy * step).coerceIn(0f, maxY)
        placePointer()
    }

    private fun placePointer() {
        pointerView.translationX = pointerX - PointerView.TIP_OFFSET
        pointerView.translationY = pointerY - PointerView.TIP_OFFSET
    }

    /** A tap at the pointer, as a touch on the WebView: it reaches cross-origin frames like any real touch. */
    private fun tapAtPointer() {
        val downTime = SystemClock.uptimeMillis()
        val x = pointerX - webView.left
        val y = pointerY - webView.top
        sendTouch(MotionEvent.ACTION_DOWN, downTime, downTime, x, y)
        handler.postDelayed({ sendTouch(MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(), x, y) }, TAP_MS)
    }

    private fun sendTouch(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float) {
        if (webViewDestroyed) return
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val event = MotionEvent.obtain(downTime, eventTime, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        webView.dispatchTouchEvent(event)
        event.recycle()
    }

    /** One mouse-wheel notch at the pointer: scrolls whatever is under it (the page, a dialog, a frame). */
    private fun scrollAtPointer(direction: Int) {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = pointerX - webView.left
            y = pointerY - webView.top
            setAxisValue(MotionEvent.AXIS_VSCROLL, -direction.toFloat())
        })
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, properties, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0)
        webView.dispatchGenericMotionEvent(event)
        event.recycle()
    }

    override fun onResume() {
        super.onResume()
        if (!webViewDestroyed) {
            webView.onResume()
            webView.resumeTimers()
        }
        handler.post(pointerCheck)
    }

    override fun onPause() {
        handler.removeCallbacks(pointerCheck)
        if (!webViewDestroyed) {
            webView.onPause()
            webView.pauseTimers()
        }
        super.onPause()
    }

    /** Home pressed, or another app opened on top. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        exitApp()
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        super.onStop()
        // Any other way off the screen (TV standby, input switch...): close entirely too.
        if (!isChangingConfigurations) exitApp()
    }

    /**
     * Leaving the app always closes it entirely: nothing is kept in memory, and it is removed from Recents too (so
     * Android drops the screenshot it keeps for the Recents thumbnail). This TV has no Recents screen anyway; the
     * app is opened from the home screen's apps row.
     */
    private fun exitApp() {
        if (!isFinishing) finishAndRemoveTask()
    }

    override fun onDestroy() {
        val exiting = isFinishing && !isChangingConfigurations
        if (exiting && !webViewDestroyed) {
            // Free the disk cache; cookies and localStorage (the login) stay.
            webView.clearCache(true)
            CookieManager.getInstance().flush()
        }
        destroyWebView()
        super.onDestroy()
        if (exiting) {
            // End the process so all of its RAM, and the WebView renderer bound to it, is released. The short
            // delay lets the WebView finish writing localStorage to disk.
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 500)
        }
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
        private const val EXTRA_URL = "url"
        private val PHONE_SITE_HOSTS = setOf("m.facebook.com", "mobile.facebook.com", "touch.facebook.com", "mbasic.facebook.com")

        /**
         * Page zoom in percent: 100 lays the page out 960 CSS px wide on a 1080p TV, with feed text about 24 px tall.
         * At 125 a single post was 1.5 screens tall. Tune this on the TV.
         */
        private const val PAGE_ZOOM_PERCENT = 100

        /** Reels only: size of the account name and caption over the video, in percent of Facebook's (100 = unchanged). */
        private const val REELS_CAPTION_SCALE_PERCENT = 70

        private const val CACHE_LIMIT_BYTES = 50L * 1024 * 1024

        /**
         * Pointer mode on/off anywhere. 5: the digits other than 0 are unused in the app, the TV only acts on them
         * when an app doesn't, and 5 is the middle of the keypad, easy to find without looking.
         */
        private const val POINTER_TOGGLE_KEY = KeyEvent.KEYCODE_5
        /** Facebook's login and check pages, where pointer mode turns on by itself. */
        private val LOGIN_PATH = Regex("""^/(login|checkpoint|recover|two_step_verification|two_factor)([/.]|$)""")
        private const val POINTER_CHECK_MS = 1000L
        /** Pointer speed in screen px: one press moves POINTER_STEP_PX; held, each repeat adds POINTER_ACCEL_PX. */
        private const val POINTER_STEP_PX = 30f
        private const val POINTER_ACCEL_PX = 6f
        private const val POINTER_MAX_STEP_PX = 90f
        private const val TAP_MS = 60L

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
