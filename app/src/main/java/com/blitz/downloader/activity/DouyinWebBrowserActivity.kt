package com.blitz.downloader.activity

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.blitz.downloader.R
import com.blitz.downloader.api.DouyinApiClient
import com.blitz.downloader.util.DouyinCookieSync

/**
 * 独立「浏览器」页：用于在 WebView 中打开抖音站点并登录，Cookie 同步至 [DouyinApiClient] 并持久化。
 */
class DouyinWebBrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etUrlBar: EditText

    /** true = 与 Chrome 电脑版一致（含登录页左侧扫码）；false = 移动站表单为主 */
    private var useDesktopUa: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_douyin_web_browser)
        setTitle(R.string.douyin_browser_title)

        useDesktopUa = !intent.getBooleanExtra(EXTRA_PREFER_MOBILE_UA, false)

        webView = findViewById(R.id.webViewBrowser)
        etUrlBar = findViewById(R.id.etUrlBar)
        val btnBack: Button = findViewById(R.id.btnBack)
        val btnGo: Button = findViewById(R.id.btnGo)
        val btnCopyUrl: Button = findViewById(R.id.btnCopyUrl)

        val goBackOrClose: () -> Unit = {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                finish()
            }
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    goBackOrClose()
                }
            },
        )

        btnBack.setOnClickListener { finish() }

        btnGo.setOnClickListener { loadUrlFromBar() }
        etUrlBar.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                loadUrlFromBar()
                true
            } else {
                false
            }
        }

        btnCopyUrl.setOnClickListener {
            val text = etUrlBar.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.browser_copy_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", text))
            Toast.makeText(this, R.string.browser_copied, Toast.LENGTH_SHORT).show()
        }

        setupWebView()


        val initial = intent.getStringExtra(EXTRA_INITIAL_URL)?.trim().orEmpty()
        val startUrl = when {
            initial.isNotEmpty() && (initial.startsWith("http://") || initial.startsWith("https://")) -> initial
            initial.isNotEmpty() -> "https://$initial"
            else -> DOUYIN_DEFAULT_HOME_URL
        }
        etUrlBar.setText(startUrl)
        webView.loadUrl(startUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // 避免旧缓存导致白屏；抖音站对缓存策略较敏感
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        // 登录页布局由服务端按 UA 区分：电脑版 UA → 与 Chrome 桌面版类似的扫码+表单；移动 UA → 多为纯表单
        settings.userAgentString = currentUserAgentString()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        // 登录/分享常见 window.open，不处理会导致白屏或无法跳转
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message,
            ): Boolean {
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                // 站点自身会大量打 %c 样式日志，避免刷屏；仅记录 error 级
                if (consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.w(TAG, "console: ${consoleMessage.message()} @ ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}")
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request?.isForMainFrame == true) {
                    Log.e(TAG, "main frame error: ${error?.description} url=${request.url}")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank()) {
                    etUrlBar.setText(url)
                    etUrlBar.setSelection(etUrlBar.text.length)
                }
                if (!url.isNullOrBlank() && url.contains("douyin.com", ignoreCase = true)) {
                    view?.post {
                        CookieManager.getInstance().flush()
                        DouyinCookieSync.syncFromCookieManager()
                    }
                }
            }
        }
    }

    private fun currentUserAgentString(): String {
        return if (useDesktopUa) {
            DouyinApiClient.webUserAgent
        } else {
            browserLikeMobileChromeUserAgent()
        }
    }


    /**
     * 系统默认 UA 去掉 `; wv`，接近普通 Chrome 移动版（无左侧 PC 扫码布局）。
     */
    private fun browserLikeMobileChromeUserAgent(): String {
        var ua = WebSettings.getDefaultUserAgent(this)
        if (ua.contains("; wv")) {
            ua = ua.replace("; wv", "")
        }
        return ua.trim()
    }

    private fun loadUrlFromBar() {
        val raw = etUrlBar.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            Toast.makeText(this, R.string.browser_url_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        webView.loadUrl(url)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DouyinWebBrowser"
        const val EXTRA_INITIAL_URL = "extra_initial_url"
        /** 为 true 时首屏使用移动 UA（与默认「电脑版」相反） */
        const val EXTRA_PREFER_MOBILE_UA = "extra_prefer_mobile_ua"

        private const val DOUYIN_DEFAULT_HOME_URL = "https://www.douyin.com/user/MS4wLjABAAAA7ZinArXxNJlWd2iiRKUI3ruz4TwjqKN5F7iqF5nGKIAgCTDtscTfMCQMor1Fn9vr?from_tab_name=main"

        fun createIntent(context: Context, initialUrl: String?): Intent {
            return Intent(context, DouyinWebBrowserActivity::class.java).apply {
                if (!initialUrl.isNullOrBlank()) {
                    putExtra(EXTRA_INITIAL_URL, initialUrl)
                }
            }
        }
    }
}
