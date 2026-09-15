package com.newzura.erebus

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.preference.PreferenceManager

/**
 * Présentation Android dédiée pour afficher YouTube ou Jellyfin directement sur la Surface
 * d'Android Auto sans capture d'écran (MediaProjection), évitant toute surchauffe et
 * permettant au téléphone d'éteindre son écran.
 */
class CarStreamPresentation(
    outerContext: Context,
    display: Display,
    private val initialUrl: String
) : Presentation(outerContext, display) {

    companion object {
        private const val TAG = "CarStream"

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        private var activePresentation: CarStreamPresentation? = null
        private var activeVirtualDisplay: VirtualDisplay? = null

        fun showPresentation(
            context: Context,
            surface: Surface,
            width: Int,
            height: Int,
            dpi: Int,
            url: String
        ): CarStreamPresentation? {
            dismissCurrent()

            try {
                val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    ?: return null

                val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY

                val targetWidth = if (width > 0) width else 1280
                val targetHeight = if (height > 0) height else 720
                val targetDpi = if (dpi > 0) dpi else 160

                Log.i(TAG, "Création VirtualDisplay pour CarStream ($targetWidth x $targetHeight, DPI=$targetDpi)")
                val vDisplay = displayManager.createVirtualDisplay(
                    "CarStreamVirtualDisplay",
                    targetWidth,
                    targetHeight,
                    targetDpi,
                    surface,
                    flags
                )

                if (vDisplay?.display == null) {
                    Log.e(TAG, "Impossible d'obtenir Display pour CarStream")
                    vDisplay?.release()
                    return null
                }

                activeVirtualDisplay = vDisplay

                val presentation = CarStreamPresentation(context, vDisplay.display, url)
                presentation.show()
                activePresentation = presentation
                return presentation
            } catch (e: Exception) {
                Log.e(TAG, "Erreur création CarStreamPresentation", e)
                dismissCurrent()
                return null
            }
        }

        fun dismissCurrent() {
            try {
                activePresentation?.dismiss()
            } catch (e: Exception) {
                Log.w(TAG, "Erreur dismiss CarStreamPresentation", e)
            }
            activePresentation = null

            try {
                activeVirtualDisplay?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Erreur release VirtualDisplay", e)
            }
            activeVirtualDisplay = null
        }

        fun getActive(): CarStreamPresentation? = activePresentation

        fun injectTouch(x: Float, y: Float, action: Int) {
            activePresentation?.postTouch(x, y, action)
        }
    }

    private var webView: WebView? = null
    private var rootContainer: FrameLayout? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootContainer = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        val wv = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val useDesktop = prefs.getBoolean("pref_youtube_desktop", true)

        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT

            if (useDesktop || initialUrl.contains("jellyfin")) {
                userAgentString = DESKTOP_USER_AGENT
            }
        }

        // Activer les cookies de session (YouTube Premium, Jellyfin Auth)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page chargée: $url")
                // Injecter CSS pour masquer les éléments superflus si besoin
                if (url?.contains("youtube.com") == true) {
                    injectYouTubeOptimizations(view)
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {}

        wv.loadUrl(initialUrl)

        rootContainer?.addView(wv)
        setContentView(rootContainer!!)
        this.webView = wv
    }

    private fun injectYouTubeOptimizations(view: WebView?) {
        val js = """
            javascript:(function() {
                var style = document.createElement('style');
                style.innerHTML = 'header, ytd-masthead, #masthead-container { opacity: 0.95; }';
                document.head.appendChild(style);
            })()
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    fun goBack(): Boolean {
        return if (webView?.canGoBack() == true) {
            webView?.goBack()
            true
        } else {
            false
        }
    }

    fun reload() {
        webView?.reload()
    }

    fun loadUrl(url: String) {
        webView?.loadUrl(url)
    }

    fun postTouch(x: Float, y: Float, action: Int) {
        val wv = webView ?: return
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        wv.dispatchTouchEvent(event)
        event.recycle()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        webView?.destroy()
        webView = null
        rootContainer = null
    }
}
