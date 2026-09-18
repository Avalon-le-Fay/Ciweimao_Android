package com.avalon.cwm.backend.online

import org.json.JSONObject
import java.util.UUID

object CiweimaoAuth {
    fun guest(config: CiweimaoClientConfig = CiweimaoClientConfig()): JSONObject {
        val parameters = config.baseParameters().apply {
            put("oauth_union_id", "")
            put("gender", "1")
            put("oauth_open_id", "")
            put("channel", config.channel)
            put("oauth_type", "")
            put("uuid", "android${UUID.randomUUID().toString().replace("-", "")}")
        }
        val response = CiweimaoTransport(config)
            .request("signup/auto_reg_v2", parameters, method = "POST")
        return extractIdentity(response)
    }

    fun needVerifyCode(
        loginName: String,
        config: CiweimaoClientConfig = CiweimaoClientConfig(),
    ): Boolean {
        val parameters = config.baseParameters().apply { put("login_name", loginName) }
        val response = try {
            CiweimaoTransport(config).request(
                "signup/use_geetest",
                parameters,
                method = "POST",
            )
        } catch (_: CiweimaoApiException) {
            return false
        }
        val value = response.optJSONObject("data")?.opt("need_use_geetest")?.toString()
        return value != null && value != "0" && value != "None" && value.isNotEmpty()
    }

    fun sendCode(
        loginName: String,
        config: CiweimaoClientConfig = CiweimaoClientConfig(),
    ) {
        val parameters = config.baseParameters().apply {
            put("verify_type", "5")
            put("login_name", loginName)
            put("phone_num", "")
            put("timestamp", System.currentTimeMillis().toString())
        }
        try {
            CiweimaoTransport(config).request(
                "signup/send_verify_code",
                parameters,
                method = "POST",
            )
        } catch (error: CiweimaoApiException) {
            if (error.code == "220008") {
                throw CiweimaoException("操作太频繁，请等 1-2 分钟再获取验证码")
            }
            throw CiweimaoException(error.tip.ifBlank { "验证码发送失败" }, error)
        }
    }

    fun passwordLogin(
        loginName: String,
        password: String,
        verifyCode: String = "",
        config: CiweimaoClientConfig = CiweimaoClientConfig(),
    ): JSONObject {
        val needsCode = needVerifyCode(loginName, config)
        val parameters = config.baseParameters().apply {
            put("login_name", loginName)
            put("passwd", password)
        }
        if (needsCode) {
            if (verifyCode.isBlank()) {
                runCatching { sendCode(loginName, config) }
                throw NeedCodeException(
                    "该账号需要验证码，已发送到你的邮箱/手机，请输入验证码后再次登录",
                )
            }
            parameters["to_code"] = "1"
            parameters["ver_code"] = verifyCode
        }
        return try {
            extractIdentity(
                CiweimaoTransport(config).request(
                    "signup/login",
                    parameters,
                    method = "POST",
                ),
            )
        } catch (error: CiweimaoApiException) {
            throw CiweimaoException(
                error.tip.ifBlank { "登录失败：用户名或密码错误" },
                error,
            )
        }
    }

    fun codeLogin(
        loginName: String,
        verifyCode: String,
        password: String = "",
        config: CiweimaoClientConfig = CiweimaoClientConfig(),
    ): JSONObject {
        val parameters = config.baseParameters().apply {
            put("login_name", loginName)
            put("passwd", password)
            put("to_code", "1")
            put("ver_code", verifyCode)
        }
        return try {
            extractIdentity(
                CiweimaoTransport(config).request(
                    "signup/login",
                    parameters,
                    method = "POST",
                ),
            )
        } catch (error: CiweimaoApiException) {
            throw CiweimaoException(
                error.tip.ifBlank { "验证码登录失败（邮箱账号请在密码框也填上密码）" },
                error,
            )
        }
    }

    private fun extractIdentity(response: JSONObject): JSONObject {
        val data = response.getJSONObject("data")
        val reader = data.getJSONObject("reader_info")
        return JSONObject()
            .put("account", reader.getString("account"))
            .put("login_token", data.getString("login_token"))
            .put("reader_name", reader.optString("reader_name"))
    }
}
