package com.avalon.cwm.backend.online

import org.json.JSONObject

internal class AccountPasswordLogin(
    private val config: CiweimaoClientConfig,
    private val request: (String, Map<String, Any?>) -> JSONObject,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val session: AccountLoginSession = AccountLoginSession(),
) {
    private fun commonParameters(): LinkedHashMap<String, String> = config.baseParameters().apply {
        if (config.isLatest) putAll(session.common(config, request))
    }

    internal fun verification(loginName: String): LoginVerification {
        val response = request("signup/use_geetest",
            commonParameters().apply { put("login_name", loginName) })
        return when (response.optJSONObject("data")?.optString("need_use_geetest")) {
            "0" -> LoginVerification.NONE
            "1" -> LoginVerification.HUMAN
            "2" -> LoginVerification.SECONDARY
            else -> throw CiweimaoException("服务器返回未知登录验证状态")
        }
    }

    fun needVerifyCode(loginName: String): Boolean = verification(loginName) == LoginVerification.SECONDARY

    fun sendCode(loginName: String) {
        require(loginName.isNotBlank()) { "请填写账号" }
        if (verification(loginName) == LoginVerification.HUMAN) throw NeedHumanVerificationException()
        val timestamp = nowMillis()
        val parameters = commonParameters().apply {
            put("verify_type", "5")
            put("login_name", loginName)
            put("phone_num", "")
            put("timestamp", timestamp.toString())
            if (config.isLatest) put("hashvalue", LatestLoginCrypto.verificationHash(getValue("account"), timestamp))
        }
        try {
            val response = request("signup/send_verify_code", parameters)
            if (config.isLatest) session.rememberCode(response)
        } catch (error: CiweimaoApiException) {
            val tip = if (error.code == "220008") "操作太频繁，请等 1-2 分钟再获取验证码"
                else error.tip.ifBlank { "验证码发送失败" }
            throw CiweimaoException(tip, error)
        }
    }

    fun login(loginName: String, password: String, verifyCode: String = "", proof: GeetestProof? = null): JSONObject {
        require(loginName.isNotBlank() && password.isNotEmpty()) { "账号和密码不能为空" }
        if (proof == null) {
            when (verification(loginName)) {
                LoginVerification.HUMAN -> throw NeedHumanVerificationException()
                LoginVerification.SECONDARY -> if (verifyCode.isBlank()) {
                    throw NeedCodeException("需要邮箱/短信二次验证码，请点击获取验证码")
                }
                LoginVerification.NONE -> Unit
            }
        }
        return submit(loginName, password, verifyCode, proof)
    }

    fun loginWithCode(loginName: String, password: String, verifyCode: String, proof: GeetestProof? = null): JSONObject {
        require(loginName.isNotBlank() && verifyCode.isNotBlank()) { "请填写账号和验证码" }
        if (config.isLatest) return login(loginName, password, verifyCode, proof)
        return submit(loginName, password, verifyCode, proof)
    }

    private fun submit(loginName: String, password: String, verifyCode: String, proof: GeetestProof?): JSONObject {
        val parameters = commonParameters().apply {
            put("login_name", loginName)
            if (config.isLatest) putAll(LatestLoginCrypto.passwordFields(loginName, password))
            else put("passwd", password)
            if (proof != null) putAll(proof.fields())
            else if (verifyCode.isNotBlank()) {
                put("to_code", if (config.isLatest) session.codeTarget() else "1")
                put("ver_code", verifyCode)
            }
        }
        return try {
            CiweimaoAuth.extractIdentity(request("signup/login", parameters))
        } catch (error: CiweimaoApiException) {
            if (error.code == "210000") throw NeedCodeException("需要邮箱/短信二次验证码，请点击获取验证码")
            throw CiweimaoException(error.tip.ifBlank { "登录失败：用户名或密码错误" }, error)
        }
    }
}
