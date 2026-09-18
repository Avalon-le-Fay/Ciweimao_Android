package com.avalon.cwm.backend.server

import com.avalon.cwm.backend.BackendRuntime
import com.avalon.cwm.backend.core.ChapterSelection
import com.avalon.cwm.backend.online.CIWEIMAO_APP_VERSION
import com.avalon.cwm.backend.online.CiweimaoAuth
import com.avalon.cwm.backend.online.CiweimaoClient
import com.avalon.cwm.backend.online.CiweimaoHttpException
import com.avalon.cwm.backend.online.NeedCodeException
import com.avalon.cwm.backend.online.OnlineDownloadRequest
import com.avalon.cwm.backend.online.stableOnlineError
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

internal class OnlineRoutes(
    private val runtime: BackendRuntime,
) : RouteModule {
    override fun handle(
        session: NanoHTTPD.IHTTPSession,
        path: String,
    ): NanoHTTPD.Response = when (path) {
        "/api/online/status" -> status()
        "/api/online/guest" -> guest(session)
        "/api/online/password_login" -> passwordLogin(session)
        "/api/online/send_code" -> sendCode(session)
        "/api/online/code_login" -> codeLogin(session)
        "/api/online/login" -> tokenLogin(session)
        "/api/online/logout" -> logout()
        "/api/online/shelf" -> shelf()
        "/api/online/download" -> download(session)
        "/api/online/progress" -> HttpSupport.json(runtime.onlineTasks.progressSnapshot())
        "/api/online/tasks" -> HttpSupport.json(runtime.onlineTasks.tasksSnapshot())
        "/api/online/tasks/clear" -> clearTasks()
        else -> HttpSupport.error("接口不存在", NanoHTTPD.Response.Status.NOT_FOUND)
    }

    private fun status(): NanoHTTPD.Response {
        val account = runtime.accounts.load()
        val name = account.optString("account")
        val token = account.optString("login_token")
        val device = runtime.accounts.accountDeviceContext(account)
        var loggedIn = name.isNotBlank() && token.isNotBlank()
        var info: Any = JSONObject.NULL
        if (loggedIn) {
            try {
                info = authClient(name, token, account, device.profile).checkLogin()
            } catch (error: Throwable) {
                loggedIn = false
                info = JSONObject()
                    .put("error", stableOnlineError(error))
                    .put(
                        "temporary",
                        error is CiweimaoHttpException &&
                            error.kind in setOf("network", "timeout"),
                    )
            }
        }
        return HttpSupport.json(
            JSONObject()
                .put("logged_in", loggedIn)
                .put("account", name)
                .put(
                    "app_version",
                    account.optString("app_version").ifBlank { CIWEIMAO_APP_VERSION },
                )
                .put("device_mode", device.mode)
                .put("device_label", device.label)
                .put("info", info),
        )
    }

    private fun guest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val payload = HttpSupport.requestJson(session)
        val version = appVersion(payload)
        return authResponse {
            val device = runtime.accounts.normaliseDeviceContext(payload)
            val identity = CiweimaoAuth.guest(runtime.accounts.authConfig(version, device.profile))
            runtime.accounts.installClient(identity, version, device)
            JSONObject().put("ok", true).put("info", identity)
        }
    }

    private fun passwordLogin(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val payload = HttpSupport.requestJson(session)
        val loginName = payload.optString("login_name").trim()
        val password = payload.optString("password")
        val verifyCode = payload.optString("ver_code").trim()
        if (loginName.isEmpty() || password.isEmpty()) {
            return HttpSupport.error("账号和密码不能为空")
        }
        val version = appVersion(payload)
        return try {
            val device = runtime.accounts.normaliseDeviceContext(payload)
            val identity = CiweimaoAuth.passwordLogin(
                loginName,
                password,
                verifyCode,
                runtime.accounts.authConfig(version, device.profile),
            )
            runtime.accounts.installClient(identity, version, device)
            HttpSupport.json(JSONObject().put("ok", true).put("info", identity))
        } catch (error: NeedCodeException) {
            HttpSupport.json(
                JSONObject()
                    .put("ok", false)
                    .put("need_code", true)
                    .put("error", error.message),
            )
        } catch (error: Throwable) {
            HttpSupport.error(stableOnlineError(error))
        }
    }

    private fun sendCode(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val payload = HttpSupport.requestJson(session)
        val loginName = payload.optString("login_name").trim()
        if (loginName.isEmpty()) return HttpSupport.error("请填写手机号/邮箱")
        return authResponse {
            val device = runtime.accounts.normaliseDeviceContext(payload)
            CiweimaoAuth.sendCode(
                loginName,
                runtime.accounts.authConfig(appVersion(payload), device.profile),
            )
            JSONObject().put("ok", true)
        }
    }

    private fun codeLogin(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val payload = HttpSupport.requestJson(session)
        val loginName = payload.optString("login_name").trim()
        val verifyCode = payload.optString("ver_code").trim()
        val password = payload.optString("password")
        if (loginName.isEmpty() || verifyCode.isEmpty()) {
            return HttpSupport.error("请填写账号和验证码")
        }
        val version = appVersion(payload)
        return authResponse {
            val device = runtime.accounts.normaliseDeviceContext(payload)
            val identity = CiweimaoAuth.codeLogin(
                loginName,
                verifyCode,
                password,
                runtime.accounts.authConfig(version, device.profile),
            )
            runtime.accounts.installClient(identity, version, device)
            JSONObject().put("ok", true).put("info", identity)
        }
    }

    private fun tokenLogin(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val payload = HttpSupport.requestJson(session)
        val account = payload.optString("account").trim()
        val token = payload.optString("login_token").trim()
        if (account.isEmpty() || token.isEmpty()) {
            return HttpSupport.error("account 和 login_token 不能为空")
        }
        val version = appVersion(payload)
        return authResponse {
            val device = runtime.accounts.normaliseDeviceContext(payload)
            val client = authClient(account, token, payload, device.profile)
            val info = client.checkLogin()
            runtime.accounts.save(account, token, version, device)
            JSONObject().put("ok", true).put("info", info)
        }
    }

    private fun logout(): NanoHTTPD.Response {
        runtime.accounts.logout()
        return HttpSupport.json(JSONObject().put("ok", true))
    }

    private fun shelf(): NanoHTTPD.Response {
        val client = runtime.accounts.client() ?: return HttpSupport.error("未登录")
        return try {
            HttpSupport.json(
                JSONObject().put("ok", true).put("books", client.getAllShelfBooks()),
            )
        } catch (error: Throwable) {
            HttpSupport.error(error.message ?: "读取书架失败")
        }
    }

    private fun download(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (runtime.accounts.client() == null) return HttpSupport.error("未登录在线账号")
        val payload = HttpSupport.requestJson(session)
        val bookId = payload.optString("book_id").trim()
        if (bookId.isEmpty()) return HttpSupport.error("书籍 ID 为空")
        val speed = payload.optString("speed", "balance")
            .takeIf { it in com.avalon.cwm.backend.online.ONLINE_DOWNLOAD_PRESETS }
            ?: "balance"
        val selection = try {
            ChapterSelection.parse(payload.opt("chapters"))
        } catch (error: IllegalArgumentException) {
            return HttpSupport.error(error.message ?: "章节范围格式错误")
        }
        return try {
            HttpSupport.json(
                runtime.onlineTasks.enqueue(
                    OnlineDownloadRequest(
                        bookId = bookId,
                        displayName = payload.optString("name").trim(),
                        speed = speed,
                        resume = if (payload.has("resume")) payload.optBoolean("resume") else true,
                        selection = selection,
                    ),
                ),
            )
        } catch (error: IllegalArgumentException) {
            HttpSupport.error(error.message ?: "无法加入下载队列")
        }
    }

    private fun clearTasks(): NanoHTTPD.Response {
        runtime.onlineTasks.clearFinished()
        return HttpSupport.json(JSONObject().put("ok", true))
    }

    private fun appVersion(payload: JSONObject): String =
        payload.optString("app_version").trim().ifBlank { CIWEIMAO_APP_VERSION }

    private fun authClient(
        account: String,
        token: String,
        payload: JSONObject,
        profile: Map<String, String>,
    ): CiweimaoClient = CiweimaoClient(
        account,
        token,
        runtime.accounts.authConfig(appVersion(payload), profile),
    )

    private fun authResponse(block: () -> JSONObject): NanoHTTPD.Response = try {
        HttpSupport.json(block())
    } catch (error: Throwable) {
        HttpSupport.error(stableOnlineError(error))
    }
}
