package io.github.a13e300.ksuwebui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.MarginLayoutParams
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.webkit.WebViewAssetLoader
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
class WebUIActivity : ComponentActivity(), FileSystemService.Listener {
    private lateinit var webviewInterface: WebViewInterface

    private lateinit var webView: WebView
    private lateinit var moduleDir: String

    @Volatile
    var isInsetsEnabled = false
        private set

    var currentInsets: Insets = Insets(0, 0, 0, 0)
        private set

    fun setInsetsEnabled(enable: Boolean) {
        isInsetsEnabled = enable
        runOnUiThread {
            updateInsetsMode()
        }
    }

    private fun updateInsetsMode() {
        if (isInsetsEnabled) {
            ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
                val inset = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                currentInsets = Insets(inset.top, inset.bottom, inset.left, inset.right)
                view.updateLayoutParams<MarginLayoutParams> {
                    leftMargin = 0
                    rightMargin = 0
                    topMargin = 0
                    bottomMargin = 0
                }
                webView.evaluateJavascript(currentInsets.js, null)
                insets
            }
            webView.requestApplyInsets()
        } else {
            ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
                val inset = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                currentInsets = Insets(inset.top, inset.bottom, inset.left, inset.right)
                view.updateLayoutParams<MarginLayoutParams> {
                    leftMargin = inset.left
                    rightMargin = inset.right
                    topMargin = inset.top
                    bottomMargin = inset.bottom
                }
                insets
            }
            webView.requestApplyInsets()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable edge to edge
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        super.onCreate(savedInstanceState)

        val moduleId = intent.getStringExtra("id")
        if (moduleId == null) {
            finish()
            return
        }
        val name = intent.getStringExtra("name") ?: moduleId
        if (name.isNotEmpty()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                setTaskDescription(ActivityManager.TaskDescription(name))
            } else {
                val taskDescription = ActivityManager.TaskDescription.Builder().setLabel(name).build()
                setTaskDescription(taskDescription)
            }
        }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        WebView.setWebContentsDebuggingEnabled(prefs.getBoolean("enable_web_debugging", BuildConfig.DEBUG))

        moduleDir = "/data/adb/modules/$moduleId"

        webView = WebView(this).apply {
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val inset = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                currentInsets = Insets(inset.top, inset.bottom, inset.left, inset.right)
                view.updateLayoutParams<MarginLayoutParams> {
                    leftMargin = inset.left
                    rightMargin = inset.right
                    topMargin = inset.top
                    bottomMargin = inset.bottom
                }
                return@setOnApplyWindowInsetsListener insets
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webviewInterface = WebViewInterface(this@WebUIActivity, this@WebUIActivity, this, moduleDir)
        }

        setContentView(webView)
        FileSystemService.start(this)
    }

    private fun setupWebview(fs: FileSystemManager) {
        val webRoot = File("$moduleDir/webroot")
        val webViewAssetLoader = WebViewAssetLoader.Builder()
            .setDomain("mui.kernelsu.org")
            .addPathHandler(
                "/",
                RemoteFsPathHandler(
                    this,
                    webRoot,
                    fs,
                    { currentInsets },
                    { enable -> setInsetsEnabled(enable) }
                )
            )
            .build()
        val webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url
                if (url.scheme.equals("ksu", ignoreCase = true) && url.host.equals("icon", ignoreCase = true)) {
                    val packageName = url.path?.substring(1)
                    if (!packageName.isNullOrEmpty()) {
                        val icon = AppIconUtil.loadAppIconSync(this@WebUIActivity, packageName, 512)
                        if (icon != null) {
                            val stream = ByteArrayOutputStream()
                            icon.compress(Bitmap.CompressFormat.PNG, 100, stream)
                            return WebResourceResponse(
                                "image/png", null, 200, "OK",
                                mapOf("Access-Control-Allow-Origin" to "*"),
                                ByteArrayInputStream(stream.toByteArray())
                            )
                        }
                    }
                }
                return webViewAssetLoader.shouldInterceptRequest(request.url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (isInsetsEnabled) {
                    webView.evaluateJavascript(currentInsets.js, null)
                }
                super.doUpdateVisitedHistory(view, url, isReload)
            }
        }
        webView.apply {
            addJavascriptInterface(webviewInterface, "ksu")
            setWebViewClient(webViewClient)
            loadUrl("https://mui.kernelsu.org/index.html")
        }
    }

    override fun onServiceAvailable(fs: FileSystemManager) {
        setupWebview(fs)
    }

    override fun onLaunchFailed() {
        Toast.makeText(this, R.string.please_grant_root, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        FileSystemService.removeListener(this)
    }
}
