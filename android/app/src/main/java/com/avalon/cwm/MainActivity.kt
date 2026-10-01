package com.avalon.cwm

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.webkit.WebViewCompat
import androidx.documentfile.provider.DocumentFile
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CwmMainActivity"
        private const val SERVER_HOST = "127.0.0.1"
        private const val SERVER_PORT = 8080
        private const val MAX_WAIT_SECONDS = 60
        private const val PERMISSION_PREFS = "cwm_permission_prompts"
        private const val NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
        private const val DOWNLOAD_PREFS = "cwm_native_downloads"
        private const val DOWNLOAD_TREE_URI = "download_tree_uri"
    }

    private val probeWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "cwm-http-probe")
    }

    private val rootCacheImporter = RootCacheImporter()
    private data class PendingNativeDownload(
        val namesJson: String? = null,
        val optionsJson: String? = null,
        val url: String? = null,
        val filename: String? = null,
        val mimeType: String? = null,
    )

    private lateinit var safDownloadController: SafDownloadController

    @Volatile
    private var pendingNativeDownload: PendingNativeDownload? = null

    private val geetestDialog by lazy { GeetestDialog(this) }
    private lateinit var webView: WebView
    private val targetUrl = "http://$SERVER_HOST:$SERVER_PORT/"

    @Volatile
    private var destroyed = false

    @Volatile
    private var trustedBridgePage = false

    private var storageDialogShown = false

    private var exitDialog: AlertDialog? = null

    @Volatile
    private var exitRequested = false

    private var systemInsetLeft = 0
    private var systemInsetTop = 0
    private var systemInsetRight = 0
    private var systemInsetBottom = 0
    private var settledImeBottom = 0
    private var settledImeVisible = false
    private var webViewOwnsImeViewport = false
    private var pageDarkTheme = false
    private var lastKeyboardState: String? = null
    private val runningImeAnimations = mutableSetOf<WindowInsetsAnimationCompat>()

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (hasBroadStorageAccess()) {
            Toast.makeText(this, "文件访问权限已开启", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "未开启文件访问权限，导入缓存和导出到公共目录可能失败",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val legacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasBroadStorageAccess()) {
            Toast.makeText(this, "文件访问权限已开启", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未授予文件访问权限", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            ServerForegroundService.refresh(applicationContext)
        } else {
            Toast.makeText(
                this,
                "未开启通知权限；本地服务仍会运行，可稍后在系统设置中开启通知",
                Toast.LENGTH_LONG,
            ).show()
        }
        maybeRequestStorageAccess()
    }

    private val downloadTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val pending = pendingNativeDownload
        pendingNativeDownload = null
        if (uri == null) {
            sendNativeDownloadEvent(JSONObject().put("type", "error").put("batch", pending?.namesJson != null).put("error", "已取消选择下载目录"))
            return@registerForActivityResult
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
            getSharedPreferences(DOWNLOAD_PREFS, MODE_PRIVATE).edit()
                .putString(DOWNLOAD_TREE_URI, uri.toString())
                .apply()
            sendNativeDownloadDirectory(uri)
            if (pending != null) startPendingNativeDownload(uri, pending)
        } catch (error: Throwable) {
            sendNativeDownloadEvent(
                JSONObject().put("type", "error")
                    .put("batch", pending?.namesJson != null)
                    .put("error", error.message ?: "无法保存目录授权"),
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureEdgeToEdgeWindow()
        setContentView(R.layout.activity_main)

        val root = findViewById<android.view.View>(R.id.root)
        webView = findViewById(R.id.webView)
        safDownloadController = SafDownloadController(
            applicationContext,
            EmbeddedServer.nativeToken,
            ::sendNativeDownloadEvent,
        )
        configureWebView()
        applyWindowInsets(root)
        configureBackNavigation()

        showLoadingPage()
        // Keep the localhost Kotlin server alive while this WebView shell is in the background.
        ServerForegroundService.start(applicationContext)
        EmbeddedServer.ensureStarted(applicationContext)
        waitServerThenLoad()

        // Only a fresh launch may prompt. On Android 13+, notification permission is
        // requested first so it doesn't overlap the existing all-files-access flow.
        if (savedInstanceState == null) {
            maybeRequestNotificationThenStorage()
        }
    }

    private fun configureEdgeToEdgeWindow() {
        // Draw WebView surfaces behind visible system bars. Interactive web content
        // receives the safe insets separately instead of shrinking the whole WebView.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        }
        val systemNight = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        applySystemBarTheme(
            getSharedPreferences("cwm_ui", MODE_PRIVATE).getBoolean("dark_theme", systemNight),
        )
    }

    private fun applySystemBarTheme(darkTheme: Boolean) {
        pageDarkTheme = darkTheme
        val surface = Color.parseColor(if (darkTheme) "#1c1c1e" else "#f2f3f5")
        // IME transitions can reveal the host between compositor frames. Match
        // all backing surfaces to the page instead of showing a fixed black band.
        window.setBackgroundDrawable(ColorDrawable(surface))
        findViewById<android.view.View>(R.id.root)?.setBackgroundColor(surface)
        if (::webView.isInitialized) webView.setBackgroundColor(surface)
        getSharedPreferences("cwm_ui", MODE_PRIVATE).edit().putBoolean("dark_theme", darkTheme).apply()
        val sdk = Build.VERSION.SDK_INT
        if (sdk >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = if (sdk >= Build.VERSION_CODES.O || darkTheme) {
                Color.TRANSPARENT
            } else {
                // Android 7.1.2 cannot draw dark navigation icons. A translucent scrim
                // keeps its white three-button keys readable while the page remains
                // visible behind the native navigation bar.
                Color.argb(144, 0, 0, 0)
            }
            if (sdk >= Build.VERSION_CODES.P) {
                window.navigationBarDividerColor = Color.TRANSPARENT
            }
            if (sdk >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme && sdk >= Build.VERSION_CODES.M
            isAppearanceLightNavigationBars = !darkTheme && sdk >= Build.VERSION_CODES.O
        }
    }

    // WebView M139+ owns IME visual-viewport resizing. Leave its native surface
    // full size: resizing it as well applies the keyboard twice. Older providers
    // receive one end-state resize, never setPadding on every animation frame.
    private fun applyImePadding(root: android.view.View, bottom: Int) {
        if (root.paddingBottom != bottom) root.setPadding(0, 0, 0, bottom)
    }

    private fun insetsForWebView(insets: WindowInsetsCompat): WindowInsetsCompat {
        if (webViewOwnsImeViewport) return insets
        // Legacy native padding already handles IME. Send explicit zeroes rather
        // than CONSUMED so hiding the IME still clears any previous viewport inset.
        return WindowInsetsCompat.Builder(insets)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
            .setVisible(WindowInsetsCompat.Type.ime(), false)
            .build()
    }

    private fun dispatchKeyboardStateToWebView(force: Boolean = false) {
        if (destroyed || !::webView.isInitialized || !trustedBridgePage) return
        val animating = runningImeAnimations.isNotEmpty()
        val visible = settledImeVisible || animating
        val state = "$visible,$animating"
        if (!force && lastKeyboardState == state) return
        lastKeyboardState = state
        webView.evaluateJavascript(
            "window.cwmSetKeyboardState && window.cwmSetKeyboardState($state);", null,
        )
    }

    private fun applyWindowInsets(root: android.view.View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val barsChanged = systemInsetLeft != bars.left || systemInsetTop != bars.top ||
                systemInsetRight != bars.right || systemInsetBottom != bars.bottom
            systemInsetLeft = bars.left
            systemInsetTop = bars.top
            systemInsetRight = bars.right
            systemInsetBottom = bars.bottom
            settledImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            // This edge-to-edge root extends behind the navigation bar. On old
            // providers use the full IME inset, not IME minus navigation-bar height.
            settledImeBottom = if (settledImeVisible) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            applyImePadding(view, if (webViewOwnsImeViewport) 0 else settledImeBottom)
            if (barsChanged) dispatchSystemInsetsToWebView()
            dispatchKeyboardStateToWebView()
            insetsForWebView(insets)
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                        runningImeAnimations.add(animation)
                        dispatchKeyboardStateToWebView()
                    }
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    // No native relayout, translation or JS bridge work per frame.
                    return insetsForWebView(insets)
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (runningImeAnimations.remove(animation) && runningImeAnimations.isEmpty()) {
                        dispatchKeyboardStateToWebView()
                    }
                }
            },
        )
        ViewCompat.requestApplyInsets(root)
    }

    private fun dispatchSystemInsetsToWebView() {
        if (destroyed || !::webView.isInitialized || !trustedBridgePage) return
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val leftCss = systemInsetLeft / density
        val topCss = systemInsetTop / density
        val rightCss = systemInsetRight / density
        val bottomCss = systemInsetBottom / density
        webView.evaluateJavascript(
            "window.cwmApplySystemInsets && " +
                "window.cwmApplySystemInsets($leftCss,$topCss,$rightCss,$bottomCss);",
            null,
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            setSupportMultipleWindows(false)
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.isLongClickable = true
        applySystemBarTheme(pageDarkTheme)
        val providerMajor = WebViewCompat.getCurrentWebViewPackage(this)?.versionName
            ?.substringBefore('.')?.toIntOrNull() ?: 0
        webViewOwnsImeViewport = providerMajor >= 139
        CookieManager.getInstance().setAcceptCookie(true)
        webView.addJavascriptInterface(RootImportBridge(), "CwmAndroid")
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.isNullOrBlank() || !isTrustedLocalUrl(url)) {
                Toast.makeText(this, "已拒绝非本地服务的下载", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            requestNativeDownload(
                PendingNativeDownload(
                    url = url,
                    filename = filename,
                    mimeType = mimeType,
                ),
            )
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                trustedBridgePage = isTrustedLocalUrl(url)
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                trustedBridgePage = isTrustedLocalUrl(url)
                super.onPageFinished(view, url)
                if (trustedBridgePage) {
                    persistWebViewCookies()
                    dispatchSystemInsetsToWebView()
                    dispatchKeyboardStateToWebView(force = true)
                    syncSystemBarThemeFromPage()
                    sendNativeDownloadDirectory()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "cwm" && uri.host == "retry") {
                    retryStartup()
                    return true
                }
                if (isTrustedLocalUri(uri)) return false

                // 不让远程页面留在拥有 Root 桥接的 WebView 内运行。
                if (uri.scheme == "http" || uri.scheme == "https") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (t: Throwable) {
                        Log.w(TAG, "Unable to open external URL", t)
                        Toast.makeText(this@MainActivity, "无法打开外部链接", Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true &&
                    request.url.toString().startsWith(targetUrl) &&
                    !isTrustedDownloadUri(request.url)
                ) {
                    showFailurePage(
                        "WebView 无法加载本地服务：${error?.description ?: "未知错误"}",
                    )
                }
            }
        }
    }

    private fun isTrustedLocalUri(uri: Uri): Boolean =
        uri.scheme == "http" &&
            uri.host == SERVER_HOST &&
            uri.port == SERVER_PORT

    private fun isTrustedLocalUrl(url: String?): Boolean = try {
        !url.isNullOrBlank() && isTrustedLocalUri(Uri.parse(url))
    } catch (_: Throwable) {
        false
    }

    private fun isTrustedDownloadUri(uri: Uri): Boolean {
        if (!isTrustedLocalUri(uri)) return false
        val path = uri.path.orEmpty()
        return path.startsWith("/download/") ||
            path.startsWith("/api/outputs/download/file/")
    }

    private fun isTrustedLocalPage(): Boolean {
        val current = webView.url ?: return false
        return try {
            isTrustedLocalUri(Uri.parse(current))
        } catch (_: Throwable) {
            false
        }
    }

    private fun syncSystemBarThemeFromPage() {
        if (destroyed || !::webView.isInitialized || !trustedBridgePage) return
        webView.evaluateJavascript(
            "window.CwmAndroid && window.CwmAndroid.setSystemBarTheme(" +
                "document.documentElement.getAttribute('data-theme'));",
            null,
        )
    }

    private inner class RootImportBridge {
        @JavascriptInterface
        fun showGeetest(optionsJson: String) {
            if (optionsJson.length > 4096 || destroyed || !trustedBridgePage) return
            runOnUiThread {
                if (destroyed || !isTrustedLocalPage()) return@runOnUiThread
                val options = try { JSONObject(optionsJson) } catch (_: Exception) { return@runOnUiThread }
                try {
                    geetestDialog.show(options) { result ->
                        if (!destroyed && isTrustedLocalPage()) {
                            webView.evaluateJavascript("window.cwmGeetestResult && window.cwmGeetestResult($result);", null)
                        }
                    }
                } catch (_: Exception) {
                    geetestDialog.dismiss()
                    val result = JSONObject().put("ok", false).put("session_id", options.optString("session_id"))
                        .put("error", "人机验证窗口初始化失败")
                    webView.evaluateJavascript("window.cwmGeetestResult && window.cwmGeetestResult($result);", null)
                }
            }
        }

        @JavascriptInterface
        fun isAvailable(): Boolean = true

        @JavascriptInterface
        fun getNativeDownloadDirectory(): String {
            if (destroyed || !trustedBridgePage) return ""
            return nativeDownloadDirectoryJson().toString()
        }

        @JavascriptInterface
        fun chooseNativeDownloadDirectory() {
            runOnUiThread {
                if (!destroyed && isTrustedLocalPage()) {
                    downloadTreeLauncher.launch(savedDownloadTreeUri())
                }
            }
        }

        @JavascriptInterface
        fun downloadOutputs(namesJson: String, optionsJson: String) {
            runOnUiThread {
                if (destroyed || !isTrustedLocalPage()) {
                    sendNativeDownloadEvent(
                        JSONObject().put("type", "error")
                            .put("batch", true)
                            .put("error", "原生下载只能从本应用本地页面发起"),
                    )
                    return@runOnUiThread
                }
                requestNativeDownload(
                    PendingNativeDownload(namesJson = namesJson, optionsJson = optionsJson),
                )
            }
        }

        @JavascriptInterface
        fun setSystemBarTheme(theme: String?) {
            if (destroyed || !trustedBridgePage) return
            val darkTheme = when {
                theme.equals("dark", ignoreCase = true) -> true
                theme.equals("light", ignoreCase = true) -> false
                else -> return
            }
            runOnUiThread {
                if (!destroyed && ::webView.isInitialized && isTrustedLocalPage()) {
                    applySystemBarTheme(darkTheme)
                }
            }
        }

        /**
         * Returns non-unique model/build information only. It deliberately excludes Android ID,
         * serial number, IMEI, MAC address, phone number and every other stable identifier.
         */
        @JavascriptInterface
        fun getDeviceProfile(): String {
            if (destroyed || !trustedBridgePage) return ""
            val abis = Build.SUPPORTED_ABIS.joinToString(",")
            return JSONObject()
                .put("device_manufacturer", Build.MANUFACTURER)
                .put("device_brand", Build.BRAND)
                .put("device_model", Build.MODEL)
                .put("device_device", Build.DEVICE)
                .put("device_product", Build.PRODUCT)
                .put("device_hardware", Build.HARDWARE)
                .put("device_board", Build.BOARD)
                .put("android_version", Build.VERSION.RELEASE)
                .put("sdk_int", Build.VERSION.SDK_INT.toString())
                .put("build_id", Build.ID)
                .put("supported_abis", abis)
                .toString()
        }

        @JavascriptInterface
        fun startRootImport(databasePath: String, booksPath: String, keysPath: String) {
            runOnUiThread {
                if (destroyed || !::webView.isInitialized || !isTrustedLocalPage()) {
                    sendRootImportFailure("Root 导入只能从本应用的本地页面发起")
                    return@runOnUiThread
                }
                if (rootCacheImporter.isBusy()) {
                    sendRootImportFailure("已有 Root 导入任务正在运行")
                    return@runOnUiThread
                }

                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("允许 Root 读取刺猬猫缓存？")
                    .setMessage(
                        "将调用 su，并由 Magisk、KernelSU 或其他超级用户管理器请求授权。\n\n" +
                            "Root 只会：\n" +
                            "1. 临时停止刺猬猫，避免数据库复制不完整；\n" +
                            "2. 读取你填写的数据库、章节和密钥；\n" +
                            "3. 复制到本应用缓存，导入后删除暂存。\n\n" +
                            "不会以 Root 身份运行本地服务，也不会执行网页传入的任意命令。",
                    )
                    .setPositiveButton("继续并请求 Root") { _, _ ->
                        beginRootImport(databasePath, booksPath, keysPath)
                    }
                    .setNegativeButton("取消") { _, _ ->
                        sendRootImportFailure("已取消 Root 导入")
                    }
                    .create()
                dialog.setOnCancelListener { sendRootImportFailure("已取消 Root 导入") }
                dialog.show()
            }
        }

        @JavascriptInterface
        fun cleanupRootImport(token: String) {
            rootCacheImporter.cleanup(token)
        }
    }

    private fun requestNativeDownload(request: PendingNativeDownload) {
        if (pendingNativeDownload != null) {
            sendNativeDownloadEvent(
                JSONObject().put("type", "error").put("batch", request.namesJson != null).put("error", "已有原生下载正在等待选择目录"),
            )
            return
        }
        val treeUri = savedDownloadTreeUri()
        if (treeUri == null) {
            pendingNativeDownload = request
            downloadTreeLauncher.launch(null)
        } else {
            startPendingNativeDownload(treeUri, request)
        }
    }

    private fun startPendingNativeDownload(treeUri: Uri, request: PendingNativeDownload) {
        when {
            request.namesJson != null && request.optionsJson != null -> {
                safDownloadController.downloadOutputs(treeUri, request.namesJson, request.optionsJson)
            }
            request.url != null && request.filename != null -> {
                safDownloadController.downloadDirect(
                    treeUri,
                    request.url,
                    request.filename,
                    request.mimeType,
                )
            }
            else -> sendNativeDownloadEvent(
                JSONObject().put("type", "error").put("batch", request.namesJson != null).put("error", "原生下载请求无效"),
            )
        }
    }

    private fun savedDownloadTreeUri(): Uri? {
        val value = getSharedPreferences(DOWNLOAD_PREFS, MODE_PRIVATE)
            .getString(DOWNLOAD_TREE_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val permission = contentResolver.persistedUriPermissions.firstOrNull {
            it.uri == uri && it.isWritePermission
        } ?: return null
        val tree = DocumentFile.fromTreeUri(this, permission.uri)
        return permission.uri.takeIf { tree != null && tree.isDirectory && tree.canWrite() }
    }

    private fun nativeDownloadDirectoryJson(): JSONObject {
        val uri = savedDownloadTreeUri()
        if (uri == null) return JSONObject().put("configured", false)
        return JSONObject()
            .put("configured", true)
            .put("name", safDownloadController.directoryLabel(uri))
            .put("uri", uri.toString())
    }

    private fun sendNativeDownloadDirectory(uri: Uri? = savedDownloadTreeUri()) {
        val payload = if (uri == null) {
            JSONObject().put("configured", false)
        } else {
            JSONObject()
                .put("configured", true)
                .put("name", safDownloadController.directoryLabel(uri))
                .put("uri", uri.toString())
        }
        runOnUiThread {
            if (destroyed || !::webView.isInitialized) return@runOnUiThread
            webView.evaluateJavascript(
                "window.cwmNativeDownloadDirectory && window.cwmNativeDownloadDirectory($payload);",
                null,
            )
        }
    }

    private fun sendNativeDownloadEvent(payload: JSONObject) {
        runOnUiThread {
            if (destroyed || !::webView.isInitialized) return@runOnUiThread
            webView.evaluateJavascript(
                "window.cwmNativeDownloadResult && window.cwmNativeDownloadResult($payload);",
                null,
            )
        }
    }

    private fun beginRootImport(databasePath: String, booksPath: String, keysPath: String) {
        rootCacheImporter.start(
            context = applicationContext,
            databaseInput = databasePath,
            booksInput = booksPath,
            keysInput = keysPath,
            callback = object : RootCacheImporter.Callback {
                override fun onSuccess(session: RootCacheImporter.Session) {
                    val payload = JSONObject()
                        .put("ok", true)
                        .put("token", session.token)
                        .put("db_path", session.databasePath)
                        .put("books_path", session.booksPath)
                        .put("keys_path", session.keysPath)
                    sendRootImportResult(payload)
                }

                override fun onFailure(message: String) {
                    sendRootImportFailure(message)
                }
            },
        )
    }

    private fun sendRootImportFailure(message: String) {
        sendRootImportResult(
            JSONObject()
                .put("ok", false)
                .put("error", message),
        )
    }

    private fun sendRootImportResult(payload: JSONObject) {
        runOnUiThread {
            if (destroyed || !::webView.isInitialized) return@runOnUiThread
            val script =
                "window.cwmRootImportResult && window.cwmRootImportResult(${payload});"
            webView.evaluateJavascript(script, null)
        }
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (destroyed || isFinishing || exitRequested) return
                if (exitDialog?.isShowing == true) return

                val insets = ViewCompat.getRootWindowInsets(webView)
                if (settledImeVisible || insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                    WindowInsetsControllerCompat(window, webView).hide(WindowInsetsCompat.Type.ime())
                    return
                }
                if (trustedBridgePage) {
                    webView.evaluateJavascript(
                        "Boolean(window.cwmDismissTopOverlay && window.cwmDismissTopOverlay())",
                    ) { handled ->
                        if (handled != "true" && !destroyed && !isFinishing && !exitRequested) showExitConfirmation()
                    }
                } else {
                    showExitConfirmation()
                }
            }
        })
    }

    private fun showExitConfirmation() {
        if (exitDialog?.isShowing == true || destroyed || isFinishing || exitRequested) return
        exitDialog = AlertDialog.Builder(this@MainActivity)
            .setTitle("是否退出？")
            .setMessage("退出后将完全关闭程序及其后台服务。")
            .setPositiveButton("是") { _, _ -> exitApplication() }
            .setNegativeButton("否", null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { exitDialog = null }
                dialog.setCanceledOnTouchOutside(true)
            }
        exitDialog?.show()
    }

    private fun exitApplication() {
        if (exitRequested) return
        exitRequested = true
        destroyed = true
        geetestDialog.dismiss()
        trustedBridgePage = false
        probeWorker.shutdownNow()

        if (::webView.isInitialized) {
            webView.stopLoading()
        }

        // Stop both owners explicitly: removing the Activity alone does not stop the
        // foreground service or the process-wide localhost server.
        ServerForegroundService.stop(applicationContext)
        finishAndRemoveTask()
    }

    private fun waitServerThenLoad() {
        probeWorker.execute {
            val ready = waitHttpReady(targetUrl, MAX_WAIT_SECONDS)
            if (destroyed) return@execute

            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (ready) {
                    EmbeddedServer.markReady()
                    webView.loadUrl(targetUrl)
                } else {
                    showFailurePage(
                        EmbeddedServer.lastError
                            ?: "等待 $MAX_WAIT_SECONDS 秒后仍无法连接 $targetUrl",
                    )
                }
            }
        }
    }

    private fun retryStartup() {
        showLoadingPage()
        ServerForegroundService.start(applicationContext)
        EmbeddedServer.retry(applicationContext)
        waitServerThenLoad()
    }

    private fun waitHttpReady(url: String, maxSeconds: Int): Boolean {
        repeat(maxSeconds) { attempt ->
            if (destroyed) return false
            if (EmbeddedServer.state == EmbeddedServer.State.FAILED) return false

            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 1000
                connection.readTimeout = 1500
                connection.requestMethod = "GET"
                connection.useCaches = false

                val code = connection.responseCode
                if (code in 200..499) return true
            } catch (t: Throwable) {
                Log.d(TAG, "Probe ${attempt + 1}/$maxSeconds: ${t.message}")
            } finally {
                connection?.disconnect()
            }

            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun hasBroadStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun maybeRequestNotificationThenStorage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            maybeRequestStorageAccess()
            return
        }
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            ServerForegroundService.refresh(applicationContext)
            maybeRequestStorageAccess()
            return
        }

        val permissionPrefs = getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE)
        if (permissionPrefs.getBoolean(NOTIFICATION_PERMISSION_ASKED, false)) {
            maybeRequestStorageAccess()
            return
        }
        permissionPrefs.edit().putBoolean(NOTIFICATION_PERMISSION_ASKED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun maybeRequestStorageAccess() {
        if (hasBroadStorageAccess() || storageDialogShown || isFinishing) return
        storageDialogShown = true

        AlertDialog.Builder(this)
            .setTitle("允许访问下载与文档目录")
            .setMessage(
                "此应用需要读取刺猬猫缓存，并把 TXT/EPUB 写入公共存储。" +
                    "下一页请选择“允许访问所有文件”。\n\n" +
                    "该权限只适合自行安装的版本；即使授权，Android 仍可能限制其他应用的私有目录。",
            )
            .setPositiveButton("前往授权") { _, _ -> openStoragePermissionPage() }
            .setNegativeButton("暂不") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun openStoragePermissionPage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appPage = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            try {
                allFilesAccessLauncher.launch(appPage)
            } catch (_: ActivityNotFoundException) {
                try {
                    allFilesAccessLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Unable to open storage settings", t)
                    Toast.makeText(this, "无法打开文件访问设置", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            legacyStorageLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    private fun showLoadingPage() {
        val bg = if (pageDarkTheme) "#101216" else "#f2f3f5"
        val fg = if (pageDarkTheme) "#eef2f8" else "#22262c"
        val sub = if (pageDarkTheme) "#aeb8c7" else "#6d747e"
        val spinnerTrack = if (pageDarkTheme) "#2b3442" else "#d9dde3"
        val accent = if (pageDarkTheme) "#4da3ff" else "#168af4"
        val html = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <style>
                *{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;
                background:${bg};color:${fg};font-family:sans-serif;padding:28px}.card{text-align:center;
                max-width:430px}.spinner{width:48px;height:48px;margin:0 auto 22px;border:4px solid ${spinnerTrack};
                border-top-color:${accent};border-radius:50%;animation:r 1s linear infinite}@keyframes r{to{transform:rotate(360deg)}}
                h2{margin:0 0 10px;font-size:22px}p{margin:0;color:${sub};line-height:1.7}
              </style>
            </head>
            <body><main class="card"><div class="spinner"></div><h2>正在启动刺猬猫下载器</h2>
            <p>正在初始化 Kotlin 后端并启动本地服务，请稍候。</p></main></body>
            </html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun showFailurePage(message: String) {
        val safeMessage = escapeHtml(message)
        val bg = if (pageDarkTheme) "#101216" else "#f2f3f5"
        val fg = if (pageDarkTheme) "#eef2f8" else "#22262c"
        val sub = if (pageDarkTheme) "#aeb8c7" else "#6d747e"
        val cardBg = if (pageDarkTheme) "#191d24" else "#ffffff"
        val cardBorder = if (pageDarkTheme) "#303847" else "#e3e6ea"
        val html = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <style>
                *{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;
                background:${bg};color:${fg};font-family:sans-serif;padding:28px}.card{width:min(100%,460px);
                padding:26px;border:1px solid ${cardBorder};border-radius:20px;background:${cardBg}}h2{margin:0 0 12px}
                p{color:${sub};line-height:1.65;overflow-wrap:anywhere;white-space:pre-wrap;text-align:left;max-height:55vh;overflow:auto;font-family:monospace;font-size:13px}a{display:inline-block;margin-top:12px;
                padding:12px 22px;border-radius:12px;background:#168af4;color:white;text-decoration:none;font-weight:700}
              </style>
            </head>
            <body><main class="card"><h2>本地服务启动失败</h2><p>$safeMessage</p>
            <a href="cwm://retry">重试</a></main></body>
            </html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun persistWebViewCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to persist WebView cookies", error)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        persistWebViewCookies()
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        exitDialog?.dismiss()
        exitDialog = null
        probeWorker.shutdownNow()
        if (::safDownloadController.isInitialized) safDownloadController.close()
        persistWebViewCookies()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }
}
