package com.avalon.cwm.backend.online

import org.json.JSONObject
import java.security.SecureRandom
import java.security.MessageDigest

internal enum class LoginVerification { NONE, HUMAN, SECONDARY }

class NeedHumanVerificationException : CiweimaoException("请完成人机验证后继续登录")

class GeetestProof(val challenge: String, val validate: String, val seccode: String) {
    init {
        require(Regex("[0-9a-fA-F]{32}([0-9a-fA-F]{2})?").matches(challenge)) { "极验 challenge 格式无效" }
        require(Regex("[0-9a-fA-F]{32}").matches(validate)) { "极验 validate 格式无效" }
        require(seccode == validate + "|jordan") { "极验 seccode 格式无效" }
    }
    fun fields(): Map<String, String> = mapOf(
        "geetest_challenge" to challenge, "geetest_validate" to validate, "geetest_seccode" to seccode,
    )
}

/** Keeps only expiring challenge metadata; never passwords, login tokens or proofs. */
internal class GeetestSessions(
    private val register: (String, CiweimaoClientConfig) -> JSONObject,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private class Pending(val binding: String, val challenge: String, val expires: Long)
    private val pending = linkedMapOf<String, Pending>()
    private val random = SecureRandom()

    fun begin(loginName: String, config: CiweimaoClientConfig): JSONObject {
        synchronized(pending) {
            pending.entries.removeAll { it.value.expires <= nowMillis() }
            require(pending.size < 16) { "待完成人机验证过多，请稍后再试" }
        }
        val registration = register(loginName, config)
        val gt = registration.optString("gt")
        val challenge = registration.optString("challenge")
        require(registration.optInt("success") == 1) { "极验服务初始化失败，请稍后重试" }
        require(Regex("[0-9a-fA-F]{32}").matches(gt)) { "极验初始化缺少有效 gt" }
        require(Regex("[0-9a-fA-F]{32}").matches(challenge)) { "极验初始化缺少有效 challenge" }
        val bytes = ByteArray(24).also(random::nextBytes)
        val id = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        synchronized(pending) {
            pending.entries.removeAll { it.value.expires <= nowMillis() }
            require(pending.size < 16) { "待完成人机验证过多，请稍后再试" }
            pending[id] = Pending(binding(loginName, config), challenge, nowMillis() + TTL_MILLIS)
        }
        return JSONObject().put("session_id", id).put("gt", gt).put("challenge", challenge)
            .put("success", 1).put("new_captcha", registration.optBoolean("new_captcha", true))
            .put("expires_in_seconds", TTL_MILLIS / 1000)
    }

    fun consume(loginName: String, config: CiweimaoClientConfig, result: JSONObject?): GeetestProof? {
        if (result == null) return null
        val id = result.optString("session_id")
        val proof = GeetestProof(result.optString("geetest_challenge"),
            result.optString("geetest_validate"), result.optString("geetest_seccode"))
        synchronized(pending) {
            val session = pending[id] ?: throw IllegalArgumentException("人机验证已失效，请重新登录")
            require(session.expires > nowMillis()) { "人机验证已过期，请重新登录" }
            require(session.binding == binding(loginName, config)) { "账号或协议已变化，请重新验证" }
            require(proof.challenge.take(32).equals(session.challenge, ignoreCase = true)) { "人机验证回调与当前会话不匹配" }
            pending.remove(id)
        }
        // Only the upstream login endpoint validates the actual human-verification result.
        return proof
    }

    fun clear() = synchronized(pending) { pending.clear() }

    private fun binding(name: String, config: CiweimaoClientConfig): String {
        val parts = listOf(name, config.appVersion, config.deviceToken, config.transportBackend) +
            config.deviceProfile.toSortedMap().flatMap { listOf(it.key, it.value) }
        val text = parts.joinToString("") { it.length.toString() + ":" + it }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    companion object { const val TTL_MILLIS = 5L * 60L * 1000L }
}
