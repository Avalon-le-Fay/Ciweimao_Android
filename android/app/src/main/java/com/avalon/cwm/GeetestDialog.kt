package com.avalon.cwm

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.avalon.cwm.backend.online.GeetestProof
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Isolated verifier: no CwmAndroid bridge, account, password, or local-server access. */
internal class GeetestDialog(private val activity: Activity) {
    private var dialog: Dialog? = null
    private var verifier: WebView? = null
    private var completed: ((JSONObject) -> Unit)? = null
    private var sessionId = ""
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = Runnable { finish(JSONObject().put("ok", false).put("error", "人机验证已过期，请重新登录")) }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(options: JSONObject, callback: (JSONObject) -> Unit) {
        if (dialog != null) {
            callback(JSONObject().put("ok", false).put("session_id", options.optString("session_id"))
                .put("error", "已有进行中的人机验证"))
            return
        }
        val id = options.optString("session_id")
        val gt = options.optString("gt")
        val challenge = options.optString("challenge")
        require(Regex("[0-9a-f]{48}").matches(id)) { "人机验证会话无效" }
        require(Regex("[0-9a-fA-F]{32}").matches(gt) && Regex("[0-9a-fA-F]{32}").matches(challenge)) {
            "极验初始化参数无效"
        }
        require(options.optInt("success") == 1) { "极验服务暂不可用" }
        val config = JSONObject().put("gt", gt).put("challenge", challenge)
            .put("new_captcha", options.optBoolean("new_captcha", true))
        val source = activity.assets.open("geetest.html").bufferedReader().use { it.readText() }
            .replace("__CWM_GEETEST_CONFIG__", config.toString())
        sessionId = id
        completed = callback
        val web = WebView(activity)
        verifier = web
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        web.setWebViewClient(object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return if (request?.isForMainFrame == true) handleNavigation(request.url) else true
            }
            @Deprecated("Compatibility with older WebView callbacks")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleNavigation(url?.let(Uri::parse))
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return denied()
                if (uri.scheme == "data" || uri.scheme == "blob") return null
                val host = uri.host.orEmpty().lowercase()
                val allowed = listOf("geetest.com", "geevisit.com", "gsensebot.com")
                    .any { host == it || host.endsWith(".$it") }
                return if (uri.scheme == "https" && uri.port in listOf(-1, 443) && allowed) null else denied()
            }
        })
        val window = Dialog(activity)
        dialog = window
        window.setTitle("人机验证")
        window.setContentView(web)
        window.setCancelable(true)
        window.setCanceledOnTouchOutside(false)
        window.setOnDismissListener {
            if (dialog === window) finish(JSONObject().put("ok", false).put("error", "已取消人机验证"))
        }
        window.show()
        window.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        web.loadDataWithBaseURL("https://captcha.cwm.invalid/", source, "text/html", "UTF-8", null)
        handler.postDelayed(expiry, 270_000L)
    }

    private fun handleNavigation(uri: Uri?): Boolean {
        if (uri?.scheme != "cwm-captcha") return true
        if (uri.host == "cancel") {
            finish(JSONObject().put("ok", false).put("error", "已取消人机验证"))
        } else if (uri.host == "error") {
            finish(JSONObject().put("ok", false).put("error", "极验组件加载或验证失败，请重新尝试"))
        } else if (uri.host == "result") {
            try {
                val raw = uri.getQueryParameter("data").orEmpty()
                require(raw.length in 1..1024)
                val data = JSONObject(raw)
                val proof = GeetestProof(data.optString("geetest_challenge"),
                    data.optString("geetest_validate"), data.optString("geetest_seccode"))
                finish(JSONObject(proof.fields()).put("ok", true))
            } catch (_: Exception) {
                finish(JSONObject().put("ok", false).put("error", "极验结果格式无效，请重新验证"))
            }
        }
        return true
    }

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private fun finish(result: JSONObject) {
        val callback = completed ?: return
        completed = null
        val id = sessionId
        sessionId = ""
        closeViews()
        callback(result.put("session_id", id))
    }

    private fun closeViews() {
        handler.removeCallbacks(expiry)
        val window = dialog
        dialog = null
        window?.setOnDismissListener(null)
        window?.dismiss()
        verifier?.apply { stopLoading(); removeAllViews(); destroy() }
        verifier = null
    }

    fun dismiss() {
        completed = null
        sessionId = ""
        closeViews()
    }
}
