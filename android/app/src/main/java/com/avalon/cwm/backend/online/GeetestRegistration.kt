package com.avalon.cwm.backend.online

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object GeetestRegistration {
    private val client = OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    fun load(loginName: String, config: CiweimaoClientConfig): JSONObject {
        require(loginName.isNotBlank() && loginName.length <= 200) { "账号无效" }
        val url = "https://app1.hbooker.com/signup/geetest_first_register".toHttpUrl().newBuilder()
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .addQueryParameter("user_id", loginName).build()
        val request = Request.Builder().url(url).header("User-Agent", config.userAgent()).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw CiweimaoException("极验初始化 HTTP " + response.code)
                val source = response.body?.source() ?: throw CiweimaoException("极验初始化响应为空")
                if (source.request(32_769)) throw CiweimaoException("极验初始化响应过大")
                return JSONObject(source.readUtf8())
            }
        } catch (error: IOException) {
            // Do not expose the URL: user_id contains the account name.
            throw CiweimaoException("极验初始化连接失败（" + error.javaClass.simpleName + "）")
        }
    }
}
