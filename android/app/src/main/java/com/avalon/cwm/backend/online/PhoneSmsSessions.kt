package com.avalon.cwm.backend.online

import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject

/** Independent, bounded SMS prelogin sessions. Tokens stay server-side in memory;
 * OTP values are not retained in the session or written to disk.
 * A successful login identity is cached until account activation succeeds, so a
 * local activation failure does not consume the same OTP a second time.
 */
internal class PhoneSmsSessions(
    private val prepare: (String, CiweimaoClientConfig) -> CiweimaoAuth.PhoneCodeSession =
        { phone, config -> CiweimaoAuth.preparePhoneCodeLogin(phone, config) },
    private val send: (CiweimaoAuth.PhoneCodeSession, CiweimaoClientConfig, GeetestProof?) -> Unit =
        { session, config, proof -> CiweimaoAuth.sendPhoneCode(session, config, proof) },
    private val authenticate: (CiweimaoAuth.PhoneCodeSession, String, CiweimaoClientConfig, GeetestProof?) -> JSONObject =
        { session, code, config, proof -> CiweimaoAuth.phoneCodeLogin(session, code, config, proof) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val binding: String, val session: CiweimaoAuth.PhoneCodeSession, var expires: Long) {
        var nextSendAt = 0L
        var codeRequested = false
        var awaitingProof = false
        var identity: JSONObject? = null
    }
    private val entries = linkedMapOf<String, Entry>()
    // Resend budget is bound to phone/config, not just the caller's session id.
    private val cooldowns = linkedMapOf<String, Long>()
    private val random = SecureRandom()

    @Synchronized
    fun sendCode(phone: String, config: CiweimaoClientConfig, sessionId: String = "", proof: GeetestProof? = null): JSONObject {
        val normalized = CiweimaoAuth.normalizeLatestPhoneNumber(phone)
        require(config.isLatest) { "手机号短信登录仅支持 2.9.366 协议" }
        prune()
        val binding = binding(normalized, config)
        val existing = if (sessionId.isBlank()) entries.entries.firstOrNull { it.value.binding == binding }
            else entries.entries.firstOrNull { it.key == sessionId }
        if (existing == null && sessionId.isNotBlank()) error("短信会话已过期，请重新获取验证码")
        val entry: Entry
        val id: String
        if (existing != null) {
            id = existing.key
            entry = existing.value
            require(entry.binding == binding) { "手机号或协议已变化，请重新获取短信验证码" }
        } else {
            require(proof == null) { "人机验证不属于当前短信会话" }
            require(entries.size < MAX_SESSIONS) { "待完成短信登录过多，请稍后再试" }
            val wait = waitSeconds(cooldowns[binding] ?: 0L)
            if (wait > 0) return JSONObject().put("ok", false).put("retry_after_seconds", wait)
                .put("error", "请等待短信发送冷却结束")
            entry = Entry(binding, prepare(normalized, config), nowMillis() + TTL_MILLIS)
            id = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            entries[id] = entry
        }
        require(entry.identity == null) { "短信登录已验证，请重试登录以保存账号，不要重新发码" }
        val wait = waitSeconds(maxOf(entry.nextSendAt, cooldowns[binding] ?: 0L))
        if (wait > 0) return envelope(id).put("ok", false).put("retry_after_seconds", wait)
            .put("code_sent", false).put("error", "请等待短信发送冷却结束")
        if (entry.awaitingProof && proof == null) return challenge(id)
        val deadline = nowMillis() + RESEND_MILLIS
        entry.nextSendAt = deadline
        cooldowns[binding] = deadline
        try {
            send(entry.session, config, proof)
            entry.codeRequested = true
            entry.awaitingProof = false
            entry.expires = nowMillis() + TTL_MILLIS
            return envelope(id).put("ok", true).put("code_sent", true)
                .put("retry_after_seconds", waitSeconds(deadline))
        } catch (_: NeedHumanVerificationException) {
            entry.awaitingProof = true
            entry.nextSendAt = 0L
            cooldowns.remove(binding) // explicit rejection: no SMS sent yet
            return challenge(id)
        } catch (error: CiweimaoApiException) {
            entry.awaitingProof = false
            return envelope(id).put("ok", false).put("code_sent", false)
                .put("retry_after_seconds", waitSeconds(deadline))
                .put("error", if (error.code == "220008") "操作太频繁，请稍后获取短信验证码"
                    else "服务器拒绝发送短信，请核对手机号或稍后重试")
        } catch (_: Exception) {
            // A lost response may still mean an SMS was delivered. Keep the
            // same session and cooldown and let the user enter a received code.
            entry.codeRequested = true
            return envelope(id).put("ok", false).put("code_sent", false)
                .put("send_uncertain", true).put("retry_after_seconds", waitSeconds(deadline))
                .put("error", "短信发送未确认；若已收到可直接登录，请勿重复点击发送")
        }
    }

    @Synchronized
    fun login(sessionId: String, phone: String, code: String, config: CiweimaoClientConfig, proof: GeetestProof? = null): JSONObject {
        require(config.isLatest) { "手机号短信登录仅支持 2.9.366 协议" }
        require(Regex("[0-9]{4,8}").matches(code)) { "请输入 4–8 位短信验证码" }
        prune()
        val entry = entries[sessionId] ?: error("短信会话已过期，请重新获取验证码")
        require(entry.binding == binding(CiweimaoAuth.normalizeLatestPhoneNumber(phone), config)) {
            "手机号或协议已变化，请重新获取短信验证码"
        }
        require(entry.codeRequested) { "请先获取短信验证码并完成人机验证" }
        entry.identity?.let { return JSONObject(it.toString()) }
        val identity = authenticate(entry.session, code, config, proof)
        require(identity.optString("account").isNotBlank() && identity.optString("login_token").isNotBlank()) {
            "短信登录响应缺少有效账号会话"
        }
        entry.identity = JSONObject(identity.toString())
        return JSONObject(identity.toString())
    }

    @Synchronized fun complete(sessionId: String) { entries.remove(sessionId) }
    @Synchronized fun clear() { entries.clear(); cooldowns.clear() }

    private fun challenge(id: String): JSONObject = envelope(id).put("ok", false)
        .put("need_geetest", true).put("code_sent", false)
    private fun envelope(id: String): JSONObject = JSONObject().put("sms_session_id", id)
        .put("expires_in_seconds", TTL_MILLIS / 1000)
    private fun waitSeconds(deadline: Long): Long = ((deadline - nowMillis() + 999L) / 1000L).coerceAtLeast(0L)
    private fun prune() {
        val now = nowMillis()
        entries.entries.removeAll { it.value.expires <= now }
        cooldowns.entries.removeAll { it.value <= now }
    }
    private fun binding(phone: String, config: CiweimaoClientConfig): String {
        val parts = listOf(phone, config.appVersion, config.deviceToken, config.channel, config.transportBackend) +
            config.deviceProfile.toSortedMap().flatMap { listOf(it.key, it.value) }
        val text = parts.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    companion object {
        const val TTL_MILLIS = 10L * 60L * 1000L
        const val RESEND_MILLIS = 60L * 1000L
        const val MAX_SESSIONS = 16
    }
}
