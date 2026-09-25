package com.avalon.cwm.backend.online

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Official account context retained in memory across password, Geetest and code steps. */
internal class AccountLoginSession {
    private var account = ""
    private var loginToken = ""
    private var toCode = ""
    private val uuid = "android" + UUID.randomUUID().toString()

    @Synchronized
    fun seed(identity: JSONObject) {
        if (account.isNotEmpty()) return
        val name = identity.text("account")
        val token = identity.text("login_token")
        if (name.isNotEmpty() && token.isNotEmpty()) {
            account = name
            loginToken = token
        }
    }

    @Synchronized
    fun common(config: CiweimaoClientConfig, request: (String, Map<String, Any?>) -> JSONObject): Map<String, String> {
        if (account.isEmpty()) {
            val parameters = config.baseParameters().apply {
                put("uuid", uuid)
                put("gender", "1")
                put("channel", config.channel)
                put("oauth_type", "")
                put("oauth_open_id", "")
                put("oauth_union_id", "")
            }
            seed(CiweimaoAuth.extractIdentity(request("signup/auto_reg_v2", parameters)))
            require(account.isNotEmpty() && loginToken.isNotEmpty()) { "预登录响应缺少有效会话" }
        }
        return mapOf("account" to account, "login_token" to loginToken)
    }

    @Synchronized
    fun rememberCode(response: JSONObject) {
        val target = response.optJSONObject("data")?.text("to_code").orEmpty()
        require(target.isNotEmpty() && target.length <= 256) { "验证码响应缺少有效 to_code" }
        toCode = target
    }

    @Synchronized
    fun codeTarget(): String = toCode.ifBlank { throw NeedCodeException("验证码会话不存在，请先获取验证码") }

    @Synchronized
    fun clear() { account = ""; loginToken = ""; toCode = "" }

    private fun JSONObject.text(key: String): String = opt(key).let {
        if (it == null || it == JSONObject.NULL) "" else it.toString().trim()
    }
}

/** Bounded cache of pending auth sessions; no credentials are written to disk. */
internal object AccountLoginSessions {
    private class Entry(val session: AccountLoginSession, var used: Long)
    private val entries = linkedMapOf<String, Entry>()
    private const val TTL_MILLIS = 30L * 60L * 1000L

    @Synchronized
    fun get(loginName: String, config: CiweimaoClientConfig): AccountLoginSession {
        val now = System.currentTimeMillis()
        val stale = entries.filterValues { now - it.used > TTL_MILLIS }.keys
        stale.forEach { entries.remove(it)?.session?.clear() }
        val key = key(loginName, config)
        val existing = entries[key]
        if (existing != null) { existing.used = now; return existing.session }
        require(entries.size < 16) { "待完成的账号登录过多，请稍后再试" }
        return AccountLoginSession().also { entries[key] = Entry(it, now) }
    }

    @Synchronized
    fun remove(loginName: String, config: CiweimaoClientConfig) {
        entries.remove(key(loginName, config))?.session?.clear()
    }

    @Synchronized
    fun clear() { entries.values.forEach { it.session.clear() }; entries.clear() }

    private fun key(name: String, config: CiweimaoClientConfig): String {
        val parts = listOf(name, config.appVersion, config.deviceToken, config.transportBackend) +
            config.deviceProfile.toSortedMap().flatMap { listOf(it.key, it.value) }
        val canonical = parts.joinToString("") { it.length.toString() + ":" + it }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
