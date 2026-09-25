package com.avalon.cwm.backend.server

import com.avalon.cwm.backend.BackendRuntime
import com.avalon.cwm.backend.core.ChapterSelection
import com.avalon.cwm.backend.online.CIWEIMAO_APP_VERSION
import com.avalon.cwm.backend.online.CIWEIMAO_LATEST_VERSION
import com.avalon.cwm.backend.online.AccountLoginSessions
import com.avalon.cwm.backend.online.PhoneSmsSessions
import com.avalon.cwm.backend.online.CiweimaoAuth
import com.avalon.cwm.backend.online.CiweimaoClient
import com.avalon.cwm.backend.online.getToc
import com.avalon.cwm.backend.online.CiweimaoClientConfig
import com.avalon.cwm.backend.online.CiweimaoHttpException
import com.avalon.cwm.backend.online.NeedHumanVerificationException
import com.avalon.cwm.backend.online.GeetestRegistration
import com.avalon.cwm.backend.online.GeetestSessions
import com.avalon.cwm.backend.online.NeedCodeException
import com.avalon.cwm.backend.online.OnlineAccountRepository
import com.avalon.cwm.backend.online.OnlineDownloadRequest
import com.avalon.cwm.backend.online.isUnreviewedChapterMeta
import com.avalon.cwm.backend.online.stableOnlineError
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

internal class OnlineRoutes(
    private val runtime: BackendRuntime,
) : RouteModule {
    private val geetestSessions = GeetestSessions(GeetestRegistration::load)
    private val phoneSmsSessions = PhoneSmsSessions()
    private val phoneGeetestSessions = GeetestSessions(register = { binding, config ->
        GeetestRegistration.load(binding.substringAfterLast('|'), config)
    })

    override fun handle(
        session: NanoHTTPD.IHTTPSession,
        path: String,
    ): NanoHTTPD.Response = when (path) {
        "/api/online/status" -> status()
        "/api/online/guest" -> guest(session)
        "/api/online/password_login" -> passwordLogin(session)
        "/api/online/send_code" -> sendCode(session)
        "/api/online/password_reset/send_code" -> passwordResetSendCode(session)
        "/api/online/password_reset/modify" -> passwordResetModify(session)
        "/api/online/code_login" -> codeLogin(session) // legacy password secondary-code route
        "/api/online/phone/areas" -> phoneAreas(session)
        "/api/online/phone/send_code" -> sendPhoneCode(session)
        "/api/online/phone/login" -> phoneCodeLogin(session)
        "/api/online/login" -> tokenLogin(session)
        "/api/online/logout" -> logout()
        "/api/online/shelf" -> shelf()
        "/api/online/chapter_selection" -> chapterSelection(session)
        "/api/online/download" -> download(session)
        "/api/online/download/verify" -> submitDownloadVerification(session)
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
            val identity = CiweimaoAuth.guest(authConfig(payload, version, device))
            activate(identity, version, device, payload)
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
            val loginConfig = authConfig(payload, version, device)
            CiweimaoAuth.seedAccountLogin(loginName, loginConfig, runtime.accounts.load())
            val identity = CiweimaoAuth.passwordLogin(
                loginName = loginName,
                password = password,
                verifyCode = verifyCode,
                config = loginConfig,
                proof = geetestSessions.consume(loginName, loginConfig, payload.optJSONObject("geetest")),
            )
            val result = activate(identity, version, device, payload)
            AccountLoginSessions.remove(loginName, loginConfig)
            HttpSupport.json(result)
        } catch (_: NeedHumanVerificationException) {
            humanVerification(payload, loginName)
        } catch (error: NeedCodeException) {
            HttpSupport.json(
                JSONObject()
                    .put("ok", false)
                    .put("need_code", true)
                    .put("code_sent", error.codeSent)
                    .put("error", error.message),
            )
        } catch (error: Throwable) {
            HttpSupport.error(stableOnlineError(error))
        }
    }

    private fun passwordResetSendCode(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = authResponse {
        val payload = HttpSupport.requestJson(session)
        val loginName = payload.optString("login_name").trim()
        val device = runtime.accounts.normaliseDeviceContext(payload)
        CiweimaoAuth.sendPasswordResetCode(loginName, authConfig(payload, appVersion(payload), device))
        JSONObject().put("ok", true)
    }

    private fun passwordResetModify(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = authResponse {
        val payload = HttpSupport.requestJson(session)
        val loginName = payload.optString("login_name").trim()
        val code = payload.optString("ver_code").trim()
        val password = payload.optString("new_password")
        require(password == payload.optString("confirm_password")) { "两次输入密码不一致" }
        val version = appVersion(payload)
        val device = runtime.accounts.normaliseDeviceContext(payload)
        // The official flow treats a completed reset as a login: activate the
        // identity returned by modify_passwd instead of asking for a new login.
        val identity = CiweimaoAuth.modifyPassword(
            loginName, code, password, authConfig(payload, version, device),
        )
        // The password is already changed at this point and the verify code is
        // consumed. If activation fails we must NOT report a plain failure: the
        // user would retry with a dead code and believe nothing happened.
        // authResponse's lambda must evaluate to a JSONObject, so this is an
        // expression, not a `return` (a return here would target the outer
        // NanoHTTPD.Response function and fail to compile).
        try {
            activate(identity, version, device, payload)
                .put("message", "密码修改成功，已自动登录")
        } catch (error: Throwable) {
            JSONObject()
                .put("ok", true)
                .put("password_changed", true)
                .put("auto_login", false)
                .put("message", "密码修改成功，但自动登录失败，请用新密码登录")
                .put("warning", stableOnlineError(error))
        }
    }

    private fun sendCode(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val payload = HttpSupport.requestJson(session)
        val loginName = payload.optString("login_name").trim()
        if (loginName.isEmpty()) return HttpSupport.error("请填写手机号/邮箱")
        val version = appVersion(payload)
        return accountAuthResponse(payload, loginName) {
            val device = runtime.accounts.normaliseDeviceContext(payload)
            val loginConfig = authConfig(payload, version, device)
            CiweimaoAuth.seedAccountLogin(loginName, loginConfig, runtime.accounts.load())
            val config = loginConfig
            CiweimaoAuth.sendCode(loginName = loginName, config = config)
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
        if (version == CIWEIMAO_LATEST_VERSION && password.isEmpty()) {
            return HttpSupport.error("请填写密码，验证码页与密码页使用同一账号认证流程")
        }
        return accountAuthResponse(payload, loginName) {
            val device = runtime.accounts.normaliseDeviceContext(payload)
            val loginConfig = authConfig(payload, version, device)
            CiweimaoAuth.seedAccountLogin(loginName, loginConfig, runtime.accounts.load())
            val identity = CiweimaoAuth.codeLogin(
                loginName = loginName,
                verifyCode = verifyCode,
                password = password,
                config = loginConfig,
                proof = geetestSessions.consume(loginName, loginConfig, payload.optJSONObject("geetest")),
            )
            val result = activate(identity, version, device, payload)
            AccountLoginSessions.remove(loginName, loginConfig)
            result
        }
    }

    private fun phoneAreas(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = authResponse {
        require(session.method == NanoHTTPD.Method.POST) { "请使用 POST" }
        val payload = HttpSupport.requestJson(session)
        val device = runtime.accounts.normaliseDeviceContext(payload)
        val config = authConfig(payload, appVersion(payload), device)
        require(config.isLatest) { "区号列表需要365或366协议" }
        val client = CiweimaoClient(com.avalon.cwm.backend.online.latest.LatestProtocol.DEFAULT_ACCOUNT, "", config)
        val response = client.call("setting/get_mobile_area", method = "POST")
        val data = response.optJSONObject("data") ?: response
        val list = data.optJSONArray("mobile_area_list") ?: throw IllegalStateException("区号列表响应缺少 mobile_area_list")
        JSONObject().put("ok", true).put("areas", list)
    }

    private fun phoneBinding(action: String, id: String, phone: String): String =
        "sms|$action|$id|${CiweimaoAuth.normalizeLatestPhoneNumber(phone)}"

    private fun sendPhoneCode(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = authResponse {
        require(session.method == NanoHTTPD.Method.POST) { "请使用 POST" }
        val payload = HttpSupport.requestJson(session)
        require(payload.optBoolean("consent", false)) { "请先确认手机号登录协议" }
        val phone = CiweimaoAuth.normalizeLatestPhoneNumber(payload.optString("phone_number"))
        val version = appVersion(payload)
        val device = runtime.accounts.normaliseDeviceContext(payload)
        val config = authConfig(payload, version, device)
        val id = payload.optString("sms_session_id")
        val proof = phoneGeetestSessions.consume(phoneBinding("send", id, phone), config, payload.optJSONObject("geetest"))
        val result = phoneSmsSessions.sendCode(phone, config, id, proof)
        if (result.optBoolean("need_geetest")) {
            val pendingId = result.getString("sms_session_id")
            result.put("geetest", phoneGeetestSessions.begin(phoneBinding("send", pendingId, phone), config))
                .put("error", "请完成人机验证后发送短信")
        }
        result
    }

    private fun phoneCodeLogin(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = authResponse {
        require(session.method == NanoHTTPD.Method.POST) { "请使用 POST" }
        val payload = HttpSupport.requestJson(session)
        require(payload.optBoolean("consent", false)) { "请先确认手机号登录协议" }
        val phone = CiweimaoAuth.normalizeLatestPhoneNumber(payload.optString("phone_number"))
        val version = appVersion(payload)
        val device = runtime.accounts.normaliseDeviceContext(payload)
        val config = authConfig(payload, version, device)
        val id = payload.optString("sms_session_id").trim()
        require(id.isNotEmpty()) { "请先获取短信验证码" }
        try {
            val proof = phoneGeetestSessions.consume(phoneBinding("login", id, phone), config, payload.optJSONObject("geetest"))
            val identity = phoneSmsSessions.login(id, phone, payload.optString("ver_code").trim(), config, proof)
            val result = activate(identity, version, device, payload)
            phoneSmsSessions.complete(id) // only after vault activation succeeds
            result
        } catch (_: NeedHumanVerificationException) {
            JSONObject().put("ok", false).put("need_geetest", true).put("sms_session_id", id)
                .put("geetest", phoneGeetestSessions.begin(phoneBinding("login", id, phone), config))
                .put("error", "请完成人机验证后短信登录")
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
            val identity = JSONObject()
                .put("account", account)
                .put("login_token", token)
                .put("reader_id", payload.optString("reader_id"))
                .put("shelf_id", payload.optString("shelf_id"))
            activate(identity, version, device, payload, info)
        }
    }

    private fun activate(
        identity: JSONObject,
        version: String,
        device: OnlineAccountRepository.DeviceContext,
        payload: JSONObject,
        checkedInfo: JSONObject? = null,
    ): JSONObject {
        val account = identity.requiredString("account")
        val token = identity.requiredString("login_token")
        val readerId = identity.optionalString("reader_id")
            .ifBlank { checkedInfo?.optionalString("reader_id").orEmpty() }
        if (version == CIWEIMAO_LATEST_VERSION && readerId.isBlank()) {
            throw IllegalArgumentException("2.9.365 登录响应缺少 reader_id")
        }
        val transport = payload.optString("transport_backend")
            .ifBlank { OnlineAccountRepository.defaultTransport(version) }
        val deviceToken = payload.optString("device_token")
            .ifBlank { com.avalon.cwm.backend.online.CIWEIMAO_DEVICE_TOKEN }
        val transient = CiweimaoClient(
            account,
            token,
            runtime.accounts.authConfig(
                appVersion = version,
                profile = device.profile,
                readerId = readerId,
                shelfId = identity.optionalString("shelf_id"),
                deviceToken = deviceToken,
                transportBackend = transport,
            ),
        )
        val info = checkedInfo ?: transient.checkLogin()
        var shelfId = identity.optionalString("shelf_id")
        if (version == CIWEIMAO_LATEST_VERSION && shelfId.isBlank()) {
            val shelves = transient.getShelfList()
            shelfId = (0 until shelves.length())
                .asSequence()
                .mapNotNull { index ->
                    shelves.optJSONObject(index)?.optionalString("shelf_id")
                }
                .firstOrNull(String::isNotBlank)
                .orEmpty()
            if (shelfId.isBlank()) {
                throw IllegalArgumentException("登录成功，但未能取得 shelf_id")
            }
        }
        val saved = JSONObject()
            .put("account", account)
            .put("login_token", token)
            .put("app_version", version)
            .put("reader_id", readerId)
            .put("shelf_id", shelfId)
            .put("device_token", deviceToken)
            .put("transport_backend", transport)
            .put("device_mode", device.mode)
            .put("device_profile", JSONObject(device.profile))
            .put("reader_name", info.optString("reader_name"))
            .put("avatar", info.optString("avatar"))
        runtime.accounts.save(
            account = account,
            loginToken = token,
            appVersion = version,
            deviceContext = device,
            readerId = readerId,
            shelfId = shelfId,
            deviceToken = deviceToken,
            transportBackend = transport,
        )
        return JSONObject().put("ok", true).put("info", info)
    }

    private fun logout(): NanoHTTPD.Response {
        geetestSessions.clear()
        phoneGeetestSessions.clear()
        phoneSmsSessions.clear()
        AccountLoginSessions.clear()
        runtime.accounts.logout()
        return HttpSupport.json(JSONObject().put("ok", true))
    }

    private fun chapterSelection(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = try {
        require(session.method == NanoHTTPD.Method.POST) { "请使用 POST" }
        val payload = HttpSupport.requestJson(session)
        val bookId = payload.optString("book_id").trim()
        require(bookId.isNotEmpty()) { "书籍 ID 为空" }
        val client = runtime.accounts.client()
            ?: throw IllegalArgumentException("未登录在线账号")
        val wallet = client.checkLogin()
        val divisions = client.getToc(bookId)
        val permissions = client.getChapterPermissionList(bookId)
        val byId = linkedMapOf<String, JSONObject>()
        for (index in 0 until permissions.length()) {
            val item = permissions.optJSONObject(index) ?: continue
            val id = item.opt("chapter_id")?.toString().orEmpty()
            if (id.isNotEmpty()) byId[id] = item
        }
        var total = 0
        var owned = 0
        var free = 0
        var unreviewed = 0
        var pendingCost = java.math.BigDecimal.ZERO
        for (divisionIndex in 0 until divisions.length()) {
            val division = divisions.optJSONObject(divisionIndex) ?: continue
            val chapters = division.optJSONArray("chapters") ?: continue
            for (chapterIndex in 0 until chapters.length()) {
                val chapter = chapters.optJSONObject(chapterIndex) ?: continue
                val permission = byId[chapter.opt("chapter_id")?.toString().orEmpty()]
                val access = permission?.opt("auth_access") ?: chapter.opt("auth_access")
                val price = permission?.opt("unit_hlb") ?: chapter.opt("unit_hlb")
                // The official buy page treats auth_access==1 as "readable", not
                // "purchased": 已购 is a readable chapter that carries a price,
                // 免费 is readable with no price. Marking every readable chapter
                // as owned labelled free chapters 已购.
                val readable = access?.toString() == "1"
                val amount = price?.toString()?.toBigDecimalOrNull()
                val purchased = readable && amount != null && amount.signum() > 0
                val pending = isUnreviewedChapterMeta(chapter.opt("chapter_title"), chapter.opt("is_valid"))
                chapter.put("auth_access", access ?: JSONObject.NULL)
                chapter.put("unit_hlb", price ?: JSONObject.NULL)
                val downloaded = (permission?.opt("is_download") ?: chapter.opt("is_download"))?.toString() == "1"
                chapter.put("owned", purchased)
                chapter.put("pending", pending)
                chapter.put("is_download", downloaded)
                chapter.put("downloadable", !downloaded && !pending)
                if (pending) unreviewed++
                else if (purchased) owned++
                else if (readable) free++
                else if (amount != null && amount.signum() > 0) pendingCost = pendingCost.add(amount)
                total++
            }
        }
        HttpSupport.json(JSONObject().put("ok", true).put("book_id", bookId)
            .put("divisions", divisions).put("total", total).put("owned", owned)
            .put("free", free).put("unreviewed", unreviewed)
            .put("pending", total - owned - free - unreviewed)
            .put("pending_cost", pendingCost.toPlainString())
            .put("balance", wallet.opt("effective_hlb") ?: JSONObject.NULL))
    } catch (error: Throwable) {
        HttpSupport.error(stableOnlineError(error))
    }

    private fun shelf(): NanoHTTPD.Response {
        val client = runtime.accounts.client() ?: return HttpSupport.error("未登录")
        return try {
            HttpSupport.json(
                JSONObject().put("ok", true).put("books", client.getAllShelfBooks()),
            )
        } catch (error: Throwable) {
            HttpSupport.error(stableOnlineError(error))
        }
    }

    private fun download(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val payload = HttpSupport.requestJson(session)
        val bookId = payload.optString("book_id").trim()
        if (bookId.isEmpty()) return HttpSupport.error("书籍 ID 为空")
        // Older web clients may still send speed; the official downloader now
        // has one fixed ten-slot behavior, so the compatibility field is ignored.
        val selection = try {
            ChapterSelection.parse(payload.opt("chapters"), payload.opt("chapter_ids"))
        } catch (error: IllegalArgumentException) {
            return HttpSupport.error(error.message ?: "章节范围格式错误")
        }
        if (runtime.accounts.client() == null) return HttpSupport.error("未登录在线账号")
        return try {
            HttpSupport.json(
                runtime.onlineTasks.enqueue(
                    OnlineDownloadRequest(
                        bookId = bookId,
                        displayName = payload.optString("name").trim(),
                        resume = if (payload.has("resume")) payload.optBoolean("resume") else true,
                        selection = selection,
                    ),
                ),
            )
        } catch (error: IllegalArgumentException) {
            HttpSupport.error(error.message ?: "无法加入下载队列")
        }
    }

    private fun submitDownloadVerification(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = try {
        require(session.method == NanoHTTPD.Method.POST) { "请使用 POST" }
        HttpSupport.json(runtime.onlineTasks.submitVerification(HttpSupport.requestJson(session)))
    } catch (error: IllegalArgumentException) {
        HttpSupport.error(error.message ?: "下载验证参数无效")
    } catch (error: IllegalStateException) {
        HttpSupport.error(error.message ?: "下载验证已失效")
    }

    private fun clearTasks(): NanoHTTPD.Response {
        runtime.onlineTasks.clearFinished()
        return HttpSupport.json(JSONObject().put("ok", true))
    }

    private fun appVersion(payload: JSONObject): String =
        payload.optString("app_version").trim().ifBlank { CIWEIMAO_APP_VERSION }

    private fun authConfig(
        payload: JSONObject,
        version: String,
        device: OnlineAccountRepository.DeviceContext,
    ) = runtime.accounts.authConfig(
        appVersion = version,
        profile = device.profile,
        readerId = payload.optString("reader_id"),
        shelfId = payload.optString("shelf_id"),
        deviceToken = payload.optString("device_token")
            .ifBlank { com.avalon.cwm.backend.online.CIWEIMAO_DEVICE_TOKEN },
        transportBackend = payload.optString("transport_backend")
            .ifBlank { OnlineAccountRepository.defaultTransport(version) },
    )

    private fun authClient(
        account: String,
        token: String,
        payload: JSONObject,
        profile: Map<String, String>,
    ): CiweimaoClient = CiweimaoClient(
        account,
        token,
        runtime.accounts.authConfig(
            appVersion = appVersion(payload),
            profile = profile,
            readerId = payload.optString("reader_id"),
            shelfId = payload.optString("shelf_id"),
            deviceToken = payload.optString("device_token")
                .ifBlank { com.avalon.cwm.backend.online.CIWEIMAO_DEVICE_TOKEN },
            transportBackend = payload.optString("transport_backend")
                .ifBlank { OnlineAccountRepository.defaultTransport(appVersion(payload)) },
        ),
    )

    private fun JSONObject.optionalString(key: String): String {
        val value = opt(key)
        return if (value == null || value == JSONObject.NULL) "" else value.toString().trim()
    }

    private fun JSONObject.requiredString(key: String): String =
        optionalString(key).ifBlank { throw IllegalArgumentException("登录响应缺少 $key") }

    private fun humanVerification(payload: JSONObject, loginName: String): NanoHTTPD.Response = try {
        val device = runtime.accounts.normaliseDeviceContext(payload)
        val registration = geetestSessions.begin(loginName, authConfig(payload, appVersion(payload), device))
        HttpSupport.json(JSONObject().put("ok", false).put("need_geetest", true)
            .put("geetest", registration).put("error", "请完成人机验证"))
    } catch (error: Throwable) { HttpSupport.error(stableOnlineError(error)) }

    private fun accountAuthResponse(payload: JSONObject, loginName: String, block: () -> JSONObject): NanoHTTPD.Response = try {
        HttpSupport.json(block())
    } catch (_: NeedHumanVerificationException) {
        humanVerification(payload, loginName)
    } catch (error: NeedCodeException) {
        HttpSupport.json(JSONObject().put("ok", false).put("need_code", true)
            .put("code_sent", error.codeSent).put("error", error.message))
    } catch (error: Throwable) { HttpSupport.error(stableOnlineError(error)) }

    private fun authResponse(block: () -> JSONObject): NanoHTTPD.Response = try {
        HttpSupport.json(block())
    } catch (error: Throwable) {
        HttpSupport.error(stableOnlineError(error))
    }
}
