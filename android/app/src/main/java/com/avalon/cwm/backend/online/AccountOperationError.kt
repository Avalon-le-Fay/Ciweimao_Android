package com.avalon.cwm.backend.online

import org.json.JSONObject

/** Stable diagnostics only; never return raw tips, URLs, request bodies or credentials. */
internal object AccountOperationError {
    fun describe(stage: String, error: Throwable): JSONObject {
        val api = error as? CiweimaoApiException
        val code = api?.code?.takeIf { Regex("[0-9]{1,12}").matches(it) }
        val path = (api?.apiPath ?: (error as? CiweimaoHttpException)?.apiPath)
            ?.takeIf { Regex("[a-z_]+/[a-z_]+").matches(it) }
        val kind: String
        val message: String
        when {
            error is CiweimaoAuthException -> {
                kind = "auth_expired"; message = "登录态已失效，请重新登录该账号"
            }
            api?.code == "310017" -> {
                kind = "human_verification"; message = "服务器要求人工验证（310017），不是余额为零或登录过期"
            }
            api?.code == "310002" -> {
                kind = "phone_binding"; message = "服务器要求绑定手机号，请在官方应用处理"
            }
            api != null -> { kind = "business"; message = "服务器拒绝此步骤" }
            error is CiweimaoHttpException -> {
                kind = error.kind.takeIf { it in setOf("timeout", "http", "network") } ?: "network"
                message = if (kind == "timeout") "请求超时，结果未确认" else "网络请求失败"
            }
            error is CiweimaoDecryptException -> { kind = "response"; message = "响应解析失败" }
            error is IllegalArgumentException && error.message.orEmpty().contains("reader_id") -> {
                kind = "account_config"; message = "保存账号缺少 reader_id，请更新该账号资料"
            }
            else -> { kind = "unknown"; message = "该步骤未完成，请刷新后核对状态" }
        }
        val label = "$stage：$message" + if (code != null && code != "310017") "（$code）" else ""
        return JSONObject().put("stage", stage).put("kind", kind).put("message", label)
            .put("code", code ?: JSONObject.NULL).put("api_path", path ?: JSONObject.NULL)
            .put("needs_verification", kind == "human_verification")
    }
}
