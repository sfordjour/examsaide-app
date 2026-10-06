package com.examsa.examsaideapp

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    companion object {
        const val ENTRY_URL = "https://learn.examsaide.com/local/examsaide/pages/home.php"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val result = if (uri != null) arrayOf(uri) else null
        filePathCallback?.onReceiveValue(result)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress_bar)

        setupWebView()
        setupFirebase()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            // App Links: if launched from an examsaide.com link (e.g. /?ref=AGENTCODE),
            // open that exact URL so referral codes and shared links land inside the app.
            webView.loadUrl(deepLinkUrl(intent) ?: ENTRY_URL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkUrl(intent)?.let { webView.loadUrl(it) }
    }

    /** Returns the https URL this activity was launched with, if it is one of ours. */
    private fun deepLinkUrl(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (intent.action != Intent.ACTION_VIEW) return null
        val url = data.toString()
        return if (isOwnHost(url)) url else null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false      // web content must not read local files
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.loadsImagesAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW   // HTTPS only
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportZoom(false)
        settings.mediaPlaybackRequiresUserGesture = false

        // Cookie persistence - keep users logged in
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // Keep everything inside the app EXCEPT things that only make sense
                // in another app. In particular, card payments bounce through the
                // bank's 3-D Secure page on a third-party domain and must stay in
                // this WebView, otherwise the user is thrown out mid-payment.
                return if (shouldOpenExternally(url)) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        // No app can handle it — ignore
                    }
                    true
                } else {
                    false // Load in WebView
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    showOfflinePage()
                }
            }

            override fun onReceivedSslError(
                view: WebView, handler: SslErrorHandler, error: SslError
            ) {
                // Never proceed past a certificate error. Google Play rejects apps that
                // call handler.proceed() here ("Unsafe implementation of onReceivedSslError").
                handler.cancel()
                if (view.url == null || view.url == error.url) {
                    showOfflinePage()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress == 100) progressBar.visibility = View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                fileChooserLauncher.launch("*/*")
                return true
            }

            // Support SCORM / H5P popup windows
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                val newWebView = WebView(this@MainActivity)
                newWebView.settings.javaScriptEnabled = true
                newWebView.settings.domStorageEnabled = true
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = newWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Only our own pages may use the camera / microphone (e.g. AI tutor voice,
                // photo upload). Any other origin is refused.
                if (isOwnHost(request.origin.toString())) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
        }
    }

    /** True for examsaide.com and its subdomains (learn., teach., www.). */
    private fun isOwnHost(url: String): Boolean {
        return try {
            val host = Uri.parse(url).host?.lowercase() ?: return false
            host == "examsaide.com" || host.endsWith(".examsaide.com")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Links that should leave the app: phone, email, WhatsApp, the Play Store,
     * YouTube and social apps. Everything else (including Paystack and any bank
     * 3-D Secure page it redirects to) stays inside the WebView.
     */
    private fun shouldOpenExternally(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("tel:") || lower.startsWith("mailto:") || lower.startsWith("sms:") ||
            lower.startsWith("whatsapp:") || lower.startsWith("intent:") || lower.startsWith("market:")) {
            return true
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return true // any other custom scheme
        }
        val externalHosts = listOf(
            "wa.me", "api.whatsapp.com", "chat.whatsapp.com",
            "play.google.com", "youtube.com", "youtu.be",
            "facebook.com", "instagram.com", "twitter.com", "x.com", "tiktok.com", "t.me"
        )
        return try {
            val host = Uri.parse(url).host?.lowercase() ?: return false
            externalHosts.any { host == it || host.endsWith(".$it") }
        } catch (e: Exception) {
            false
        }
    }

    private fun showOfflinePage() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        background: #0a1628;
                        color: #fff;
                        font-family: -apple-system, sans-serif;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        min-height: 100vh;
                        text-align: center;
                        padding: 24px;
                    }
                    .logo { font-size: 48px; margin-bottom: 24px; }
                    h2 { color: #009dae; font-size: 22px; margin-bottom: 12px; }
                    p { color: #8899aa; font-size: 15px; margin-bottom: 32px; line-height: 1.5; }
                    button {
                        background: #009dae;
                        color: #fff;
                        border: none;
                        padding: 14px 36px;
                        border-radius: 8px;
                        font-size: 16px;
                        font-weight: 600;
                        cursor: pointer;
                    }
                </style>
            </head>
            <body>
                <div class="logo">📚</div>
                <h2>No Connection</h2>
                <p>Please check your internet connection and try again.</p>
                <button onclick="window.location.href='$ENTRY_URL'">Retry</button>
            </body>
            </html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun setupFirebase() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                @Suppress("UNUSED_VARIABLE")
                val token = task.result
                // TODO: POST token to learn.examsaide.com for push targeting
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }
}
