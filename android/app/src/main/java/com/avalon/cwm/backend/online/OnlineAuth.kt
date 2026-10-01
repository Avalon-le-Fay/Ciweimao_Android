package com.avalon.cwm.backend.online

import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/** Authentication flows shared by the legacy and exact 2.9.365 protocols. */
object CiweimaoAuth {
    internal data class PhoneCodeSession(
        val phoneNumber: String,
        val account: String,
        val loginToken: String,
        val uuid: String,
        val sentAtMillis: Long,
    ) {
        override fun toString(): String = "PhoneCodeSession(<redacted>)"
    }

    private const val LOWER_HEX = "0123456789abcdef"
    private val verificationRandom = SecureRandom()

    internal fun normalizeLatestPhoneNumber(value: String): String {
        val compact = value.trim().replace(Regex("""\s+"""), "")
        val pair = Regex("""^([1-9]\d{0,5})-(\d{4,15})$""").matchEntire(compact)
        if (pair != null) {
            val area = pair.groupValues[1]
            val number = pair.groupValues[2]
            require(area != "1" || Regex("""^1\d{10}$""").matches(number)) { "请输入大陆11位手机号" }
            return "$area-$number"
        }
        val national = when {
            Regex("""^1\d{10}$""").matches(compact) -> compact
            Regex("""^\+861\d{10}$""").matches(compact) -> compact.substring(3)
            Regex("""^861\d{10}$""").matches(compact) -> compact.substring(2)
            else -> throw IllegalArgumentException("请选择国家/地区并输入本地手机号")
        }
        return "1-$national"
    }

    internal fun generateVerificationHash(): String {
        val bytes = ByteArray(16)
        verificationRandom.nextBytes(bytes)
        return buildString(32) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(LOWER_HEX[value ushr 4]).append(LOWER_HEX[value and 0x0f])
            }
        }
    }

    fun guest(
        config: CiweimaoClientConfig = CiweimaoClientConfig(),
        uuid: String = if (config.isLatest) {
            "android${UUID.randomUUID()}"
        } else {
            "android${UUID.randomUUID().toString().replace("-", "")}"
        },
    ): JSONObject {
        val parameters = config.baseParameters().apply {
            put("oauth_union_id", "")
            put("gender", "1")
            put("oauth_open_id", "")
            put("channel", config.channel)
            put("oauth_type", "")
            put("uuid", uuid)
        }
        val response = CiweimaoTransport(config)
            .request("signup/auto_reg_v2", parameters, method = "POST")
        return extractIdentity(response).put("uuid", uuid)
    }

    fun needVerifyCode(
        loginName: String,
        config: CiweimaoClientConfig = CiweimaoClientConfig(),
    ): Boolean = accountLogin(config, loginName).needVerifyCode(loginName)

    /**
     * Official 366 ForgotPassActivity step 1 (GetSMSTask, request 78).
     *
     * GetSMSTask computes hashvalue = MD5(Base64(AES(account + timestamp))) over the
     * *session* account, never over the typed email/phone. Verified against the
     * official 2.9.366 capture: account=书客8366014, timestamp=1789338043283,
     * hashvalue=3c8a5ab56180ee9adb481fe26aecd934.
     *
     * The reset flow therefore starts from a transient pre-login identity
     * (signup/auto_reg_v2). The server resolves the typed email/phone to the bound
     * account itself and returns that account's identity on step 2.
     */
    fun sendPasswordResetCode(
        loginName: String,
        config: CiweimaoClientConfig = CiweimaoClientConfig(),
    ): JSONObject {
        val name = loginName.trim()
        require(name.isNotBlank()) { "请填写手机号或邮箱" }
        require(config.isLatest) { "忘记密码仅支持 2.9.366 协议" }
        val session = passwordResetSession(name, config)
        val timestamp = System.currentTimeMillis()
        val params = config.baseParameters().apply {
            put("account", session.account)
            put("login_token", session.loginToken)
            put("verify_type", "2")
            put("timestamp", timestamp.toString())
            put("hashvalue", LatestLoginCrypto.verificationHash(session.account, timestamp))
            if (name.contains("@")) put("email", name) else put("phone_num", normalizeLatestPhoneNumber(name))
        }
        return postLatest("signup/send_verify_code", params, config)
    }

    /** One transient pre-login identity shared by send-code and modify-password. */
    internal data class PasswordResetSession(
        val loginName: String,
        val account: String,
        val loginToken: String,
    ) {
        override fun toString(): String = "PasswordResetSession(<redacted>)"
    }

    private fun passwordResetSession(
        loginName: String,
        config: CiweimaoClientConfig,
    ): PasswordResetSession = PasswordResetSessions.get(loginName, config.appVersion) {
        val identity = guest(config.copy(retries = 1), "android" + UUID.randomUUID())
        PasswordResetSession(
            loginName = loginName,
            account = identity.getString("account").trim(),
            loginToken = identity.getString("login_token").trim(),
        )
    }

    private fun postLatest(
        path: String,
        parameters: Map<String, Any?>,
        config: CiweimaoClientConfig,
    ): JSONObject = CiweimaoTransport(config).request(path, parameters, method = "POST", retries = 1)

    /**
     * Official 366 ForgotPassActivity: signup/modify_passwd (81) already returns
     * BindPhoneData{login_token,user_code,reader_info,prop_info}, and the client
     * saves that identity and enters the main frame. So a successful password
     * reset IS a login; the caller must activate this identity, not ask again.
     */
    fun modifyPassword(loginName: String, verifyCode: String, newPassword: String,
                       config: CiweimaoClientConfig = CiweimaoClientConfig()): JSONObject {
        val name = loginName.trim()
        require(name.isNotBlank() && verifyCode.isNotBlank() && newPassword.isNotEmpty()) { "请填写完整的改密信息" }
        require(newPassword.length in 6..15) { "密码需为 6-15 位" }
        require(config.isLatest) { "忘记密码仅支持 2.9.366 协议" }
        // Official 366 carries account + login_token on signup/modify_passwd and no
        // verify_type/hashvalue. The identity must be the same one that sent the
        // code, otherwise the server rejects the pairing.
        // Reuse the exact identity that sent the code. Creating a fresh guest here
        // would silently desynchronise the account from the issued verify code.
        val session = PasswordResetSessions.find(name, config.appVersion)
            ?: throw CiweimaoException("验证码会话已过期，请重新获取验证码")
        val params = config.baseParameters().apply {
            put("account", session.account)
            put("login_token", session.loginToken)
            put("ver_code", verifyCode.trim())
            put("passwd", newPassword)
            if (name.contains("@")) put("email", name) else put("phone_num", normalizeLatestPhoneNumber(name))
        }
        // Account-security mutation: never replay on an unknown commit state.
        val response = postLatest("signup/modify_passwd", params, config)
        val identity = extractIdentity(response)
        PasswordResetSessions.remove(name, config.appVersion)
        return identity
    }

    /** Account/password secondary verification for both protocol versions. */
    fun sendCode(
        loginName: String,
        config: CiweimaoClientConfig = CiweimaoClientConfig(),
    ) = accountLogin(config, loginName).sendCode(loginName)

    internal fun seedAccountLogin(loginName: String, config: CiweimaoClientConfig, identity: JSONObject) {
        if (config.isLatest && identity.optString("app_version") == config.appVersion) {
            AccountLoginSessions.get(loginName, config).seed(identity)
        }
    }

    private fun accountLogin(config: CiweimaoClientConfig, loginName: String): AccountPasswordLogin {
        val transport = CiweimaoTransport(config)
        return AccountPasswordLogin(config, { path, parameters ->
            transport.request(path, parameters, method = "POST", retries = 1)
        }, session = if (config.isLatest) AccountLoginSessions.get(loginName, config) else AccountLoginSession())
    }

    internal fun preparePhoneCodeLogin(
        loginName: String,
        config: CiweimaoClientConfig,
    ): PhoneCodeSession {
        require(config.isLatest) { "手机号短信登录仅支持 2.9.366 协议" }
        val phoneNumber = normalizeLatestPhoneNumber(loginName)
        val uuid = "android${UUID.randomUUID()}"
        // This transient identity never replaces the saved/active account.
        val identity = guest(config.copy(retries = 1), uuid)
        return PhoneCodeSession(phoneNumber, identity.getString("account"),
            identity.getString("login_token"), uuid, 0L)
    }

    internal fun beginPhoneCodeLogin(
        loginName: String,
        config: CiweimaoClientConfig,
        existingSession: PhoneCodeSession? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): PhoneCodeSession {
        val session = existingSession ?: preparePhoneCodeLogin(loginName, config)
        require(session.phoneNumber == normalizeLatestPhoneNumber(loginName)) { "短信会话与手机号不匹配" }
        sendPhoneCode(session, config, nowMillis = nowMillis)
        return session.copy(sentAtMillis = nowMillis)
    }

    internal fun sendPhoneCode(
        session: PhoneCodeSession,
        config: CiweimaoClientConfig,
        proof: GeetestProof? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        require(config.isLatest) { "手机号短信登录仅支持 2.9.366 协议" }
        // GetSMSTask: MD5(trim(AndroidBase64(AES(account + timestamp)))).
        // A random hex string is not an equivalent verification hash.
        val parameters = buildPhoneCodeSendParameters(session, config,
            LatestLoginCrypto.verificationHash(session.account, nowMillis), nowMillis)
        proof?.let { parameters.putAll(it.fields()) }
        requestVerificationCode(parameters, config)
    }

    internal fun buildPhoneCodeSendParameters(
        session: PhoneCodeSession,
        config: CiweimaoClientConfig,
        hashValue: String,
        nowMillis: Long,
    ): LinkedHashMap<String, Any?> = linkedMapOf(
        "account" to session.account,
        "app_version" to config.appVersion,
        "device_token" to config.deviceToken,
        "hashvalue" to hashValue,
        "login_name" to session.phoneNumber,
        "login_token" to session.loginToken,
        "phone_num" to "",
        "timestamp" to nowMillis.toString(),
        "verify_type" to "6",
    )

    private fun requestVerificationCode(
        parameters: Map<String, Any?>,
        config: CiweimaoClientConfig,
    ) {
        try {
            CiweimaoTransport(config).request(
                "signup/send_verify_code",
                parameters,
                method = "POST",
                retries = 1,
            )
        } catch (error: CiweimaoApiException) {
            if (error.code == "310017") throw NeedHumanVerificationException()
            // Preserve explicit business rejection versus delivery uncertainty.
            // PhoneSmsSessions handles rate limiting without replaying the SMS.
            throw error
        }
    }

    fun passwordLogin(
        loginName: String,
        password: String,
        verifyCode: String = "",
        config: CiweimaoClientConfig = CiweimaoClientConfig(),
        proof: GeetestProof? = null,
    ): JSONObject = accountLogin(config, loginName).login(loginName, password, verifyCode, proof)

    /** Exact official 2.9.365 SMS login_v2 shape using the send-code session. */
    internal fun phoneCodeLogin(
        session: PhoneCodeSession,
        verifyCode: String,
        config: CiweimaoClientConfig,
        proof: GeetestProof? = null,
    ): JSONObject {
        require(config.isLatest) { "phoneCodeLogin 仅支持 2.9.366" }
        require(Regex("[0-9]{4,8}").matches(verifyCode)) { "请输入 4–8 位短信验证码" }
        return try {
            extractIdentity(
                CiweimaoTransport(config).request(
                    "signup/login_v2",
                    buildPhoneCodeLoginParameters(session, verifyCode, config).apply {
                        proof?.let { putAll(it.fields()) }
                    },
                    method = "POST",
                    retries = 1,
                ),
            )
        } catch (error: CiweimaoApiException) {
            if (error.code == "310017") throw NeedHumanVerificationException()
            throw error
        }
    }

    internal fun buildPhoneCodeLoginParameters(
        session: PhoneCodeSession,
        verifyCode: String,
        config: CiweimaoClientConfig,
    ): LinkedHashMap<String, Any?> = linkedMapOf(
        "account" to session.account,
        "app_version" to config.appVersion,
        "channel" to config.channel,
        "device_token" to config.deviceToken,
        "login_token" to session.loginToken,
        "phone_num" to session.phoneNumber,
        "uuid" to session.uuid,
        "ver_code" to verifyCode,
    )

    fun codeLogin(
        loginName: String,
        verifyCode: String,
        password: String = "",
        config: CiweimaoClientConfig = CiweimaoClientConfig(),
        proof: GeetestProof? = null,
    ): JSONObject {
        return accountLogin(config, loginName).loginWithCode(loginName, password, verifyCode, proof)
    }

    internal fun extractIdentity(response: JSONObject): JSONObject {
        val data = response.getJSONObject("data")
        val reader = data.optJSONObject("reader_info")
            ?: response.optJSONObject("reader_info")
            ?: throw IllegalArgumentException("改密响应缺少 reader_info")
        val account = reader.optString("account").trim()
            .ifBlank { reader.optString("user_code").trim() }
            .ifBlank { data.optString("user_code").trim() }
            .ifBlank { throw IllegalArgumentException("改密响应缺少账号标识") }
        val token = data.optString("login_token").trim()
            .ifBlank { response.optString("login_token").trim() }
            .ifBlank { throw IllegalArgumentException("改密响应缺少 login_token") }
        return JSONObject()
            .put("account", account)
            .put("login_token", token)
            .put("reader_name", reader.optString("reader_name"))
            .put("reader_id", reader.opt("reader_id") ?: JSONObject.NULL)
            .put("shelf_id", reader.opt("shelf_id") ?: JSONObject.NULL)
    }
}


/**
 * Bounded cache of transient pre-login identities for the two-step password reset.
 *
 * Keyed by (login name, app version) instead of the full device context so that a
 * device-profile change between "send code" and "modify password" cannot silently
 * rotate the session and desynchronise the account from the issued verify code.
 */
internal object PasswordResetSessions {
    private class Entry(val session: CiweimaoAuth.PasswordResetSession, var used: Long)

    private val entries = linkedMapOf<String, Entry>()
    private const val TTL_MILLIS = 30L * 60L * 1000L
    private const val MAX_ENTRIES = 16

    @Synchronized
    fun get(
        loginName: String,
        appVersion: String,
        create: () -> CiweimaoAuth.PasswordResetSession,
    ): CiweimaoAuth.PasswordResetSession {
        val now = System.currentTimeMillis()
        entries.entries.removeAll { now - it.value.used > TTL_MILLIS }
        val key = key(loginName, appVersion)
        entries[key]?.let { it.used = now; return it.session }
        require(entries.size < MAX_ENTRIES) { "待完成的密码重置过多，请稍后再试" }
        val session = create()
        require(session.account.isNotBlank() && session.loginToken.isNotBlank()) {
            "预登录响应缺少有效会话，请稍后重试"
        }
        entries[key] = Entry(session, now)
        return session
    }

    @Synchronized
    fun find(loginName: String, appVersion: String): CiweimaoAuth.PasswordResetSession? {
        val now = System.currentTimeMillis()
        entries.entries.removeAll { now - it.value.used > TTL_MILLIS }
        val entry = entries[key(loginName, appVersion)] ?: return null
        entry.used = now
        return entry.session
    }

    @Synchronized
    fun remove(loginName: String, appVersion: String) {
        entries.remove(key(loginName, appVersion))
    }

    @Synchronized
    fun clear() { entries.clear() }

    private fun key(loginName: String, appVersion: String): String =
        "${loginName.length}:$loginName|${appVersion.length}:$appVersion"
}
