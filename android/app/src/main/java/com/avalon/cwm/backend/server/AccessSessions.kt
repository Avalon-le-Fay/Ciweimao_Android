package com.avalon.cwm.backend.server

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

class AccessSessions(context: Context) {
    companion object {
        const val COOKIE_NAME = "cwm_session"
        const val DEFAULT_PASSWORD = "@admin325"
        private const val PREFERENCES = "cwm_http_sessions"
        private const val TOKEN_KEY = "session_token"
        private const val COOKIE_MAX_AGE_SECONDS = 3650L * 24L * 60L * 60L
    }

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val token: String by lazy {
        preferences.getString(TOKEN_KEY, null)?.takeIf(String::isNotBlank) ?: generateToken().also {
            check(preferences.edit().putString(TOKEN_KEY, it).commit()) {
                "无法持久化访问会话"
            }
        }
    }

    fun validatePassword(candidate: String): Boolean = constantTimeEquals(
        candidate.toByteArray(Charsets.UTF_8),
        DEFAULT_PASSWORD.toByteArray(Charsets.UTF_8),
    )

    fun isAuthenticated(cookieHeader: String?): Boolean {
        val supplied = cookieHeader.orEmpty().split(';')
            .map(String::trim)
            .firstOrNull { it.startsWith("$COOKIE_NAME=") }
            ?.substringAfter('=')
            .orEmpty()
        return supplied.isNotEmpty() && constantTimeEquals(
            supplied.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8),
        )
    }

    fun setCookieHeader(): String =
        "$COOKIE_NAME=$token; Path=/; Max-Age=$COOKIE_MAX_AGE_SECONDS; HttpOnly; SameSite=Lax"

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean =
        MessageDigest.isEqual(left, right)
}
