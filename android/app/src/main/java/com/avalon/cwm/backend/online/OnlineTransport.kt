package com.avalon.cwm.backend.online

import com.avalon.cwm.backend.core.CiweimaoCrypto
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class CiweimaoTransport(private val config: CiweimaoClientConfig) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun request(
        path: String,
        parameters: Map<String, Any?>,
        method: String = "GET",
        retries: Int = config.retries,
        checkCode: Boolean = true,
    ): JSONObject {
        val attempts = retries.coerceAtLeast(1)
        var lastHttp: CiweimaoHttpException? = null
        for (attempt in 0 until attempts) {
            try {
                val request = buildRequest(path, parameters, method)
                val response = client.newCall(request).execute()
                try {
                    if (response.code >= 500) {
                        lastHttp = CiweimaoHttpException(
                            "HTTP ${response.code} @$path",
                            kind = "http",
                            apiPath = path,
                        )
                        if (attempt + 1 < attempts) backoff(attempt)
                        continue
                    }
                    if (response.code >= 400) {
                        throw CiweimaoHttpException(
                            "HTTP ${response.code} @$path",
                            kind = "http",
                            apiPath = path,
                        )
                    }
                    val encrypted = response.body?.string().orEmpty()
                    val decrypted = try {
                        CiweimaoCrypto.decryptOnlineResponse(encrypted, config.seed)
                    } catch (error: Throwable) {
                        throw CiweimaoDecryptException("AES 解密失败: ${error.message}", error)
                    }
                    val json = try {
                        JSONObject(decrypted.toString(Charsets.UTF_8))
                    } catch (error: Throwable) {
                        throw CiweimaoDecryptException(
                            "响应解密后非 JSON @$path: ${error.message}",
                            error,
                        )
                    }
                    if (checkCode) checkBusinessCode(json, path)
                    return json
                } finally {
                    response.close()
                }
            } catch (error: InterruptedIOException) {
                lastHttp = CiweimaoHttpException(
                    "网络超时 @$path",
                    kind = "timeout",
                    apiPath = path,
                    causeType = error.javaClass.simpleName,
                    cause = error,
                )
                if (attempt + 1 < attempts) backoff(attempt)
            } catch (error: IOException) {
                lastHttp = CiweimaoHttpException(
                    "网络连接失败 @$path",
                    kind = "network",
                    apiPath = path,
                    causeType = error.javaClass.simpleName,
                    cause = error,
                )
                if (attempt + 1 < attempts) backoff(attempt)
            }
        }
        throw lastHttp ?: CiweimaoHttpException("请求多次失败 @$path", apiPath = path)
    }

    private fun buildRequest(
        path: String,
        parameters: Map<String, Any?>,
        method: String,
    ): Request {
        val base = CIWEIMAO_BASE_URL.toHttpUrl().resolve(path)
            ?: throw IllegalArgumentException("API 路径无效: $path")
        val request = Request.Builder().header("User-Agent", config.userAgent())
        if (method.equals("POST", ignoreCase = true)) {
            val form = FormBody.Builder().apply {
                parameters.forEach { (key, value) -> add(key, wireValue(value)) }
            }.build()
            request.url(base).post(form)
        } else {
            val url = base.newBuilder().apply {
                parameters.forEach { (key, value) -> addQueryParameter(key, wireValue(value)) }
            }.build()
            request.url(url).get()
        }
        return request.build()
    }

    private fun wireValue(value: Any?): String = when (value) {
        null -> ""
        is Boolean -> if (value) "1" else "0"
        else -> value.toString()
    }

    private fun checkBusinessCode(json: JSONObject, path: String) {
        val code = json.opt("code")?.toString().orEmpty()
        if (code == CIWEIMAO_OK_CODE) return
        val tip = json.optString("tip").ifBlank {
            json.optString("error").ifBlank { "未知业务错误" }
        }
        if (code in CIWEIMAO_AUTH_CODES) {
            throw CiweimaoAuthException(code, tip, path)
        }
        throw CiweimaoApiException(code, tip, path)
    }

    private fun backoff(attempt: Int) {
        try {
            Thread.sleep(500L * (attempt + 1))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
