package com.avalon.cwm.backend.online

import com.avalon.cwm.backend.core.CiweimaoCrypto
import com.avalon.cwm.backend.online.latest.LatestProtocol
import com.avalon.cwm.backend.online.latest.OfficialNativeTransport
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private data class RawHttpResponse(
    val statusCode: Int,
    val body: String,
)

/**
 * Version-aware Ciweimao transport.
 *
 * 2.9.320 remains the old compatibility path. Exact 2.9.365 uses the captured
 * signed raw form protocol over the native libcurl/OpenSSL bridge by default.
 * Android's OkHttp stack remains an explicit compatibility option. The request body
 * is assembled before transport selection, so both adapters receive the same bytes.
 */
class CiweimaoTransport(private val config: CiweimaoClientConfig) {
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
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
        val cleanPath = "/" + path.trimStart('/')
        val attempts = retries.coerceAtLeast(1)
        var lastNetwork: CiweimaoHttpException? = null

        for (attempt in 0 until attempts) {
            try {
                val response = if (config.isLatest) {
                    requestLatest(cleanPath, parameters, method)
                } else {
                    requestLegacy(cleanPath, parameters, method)
                }
                if (response.statusCode >= 500) {
                    lastNetwork = CiweimaoHttpException(
                        "HTTP ${response.statusCode} @$cleanPath",
                        kind = "http",
                        apiPath = cleanPath,
                        retryable = true,
                    )
                    if (attempt + 1 < attempts) backoff(attempt)
                    continue
                }
                if (response.statusCode >= 400) {
                    throw CiweimaoHttpException(
                        "HTTP ${response.statusCode} @$cleanPath",
                        kind = "http",
                        apiPath = cleanPath,
                    )
                }
                // BuyDownThread treats an empty transport response as a network
                // failure. Keep it retryable instead of misclassifying it as an
                // AES/decode or business error.
                if (response.body.isBlank()) {
                    lastNetwork = CiweimaoHttpException(
                        "接口响应为空 @$cleanPath",
                        kind = "network",
                        apiPath = cleanPath,
                    )
                    if (attempt + 1 < attempts) backoff(attempt)
                    continue
                }

                val decrypted = try {
                    CiweimaoCrypto.decryptOnlineResponse(response.body, config.seed)
                } catch (error: Throwable) {
                    throw CiweimaoDecryptException("AES 解密失败: ${error.message}", error)
                }
                val json = try {
                    JSONObject(decrypted.toString(Charsets.UTF_8))
                } catch (error: Throwable) {
                    throw CiweimaoDecryptException(
                        "响应解密后非 JSON @$cleanPath: ${error.message}",
                        error,
                    )
                }
                if (checkCode) checkBusinessCode(json, cleanPath)
                return json
            } catch (error: CiweimaoHttpException) {
                if (error.kind == "http") throw error
                lastNetwork = error
                if (attempt + 1 < attempts) backoff(attempt)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CiweimaoHttpException(
                    "请求被中断 @$cleanPath",
                    kind = "timeout",
                    apiPath = cleanPath,
                    causeType = error.javaClass.simpleName,
                    cause = error,
                )
            } catch (error: IOException) {
                val detail = error.message
                    ?.filter { it.code >= 0x20 && it.code != 0x7f }
                    ?.take(300)
                    ?.ifBlank { null } ?: error.javaClass.simpleName
                lastNetwork = CiweimaoHttpException(
                    "接口连接失败 @$cleanPath: $detail",
                    kind = "network",
                    apiPath = cleanPath,
                    causeType = error.javaClass.simpleName,
                    cause = error,
                )
                if (attempt + 1 < attempts) backoff(attempt)
            }
        }
        throw lastNetwork ?: CiweimaoHttpException(
            "请求多次失败 @$cleanPath",
            apiPath = cleanPath,
        )
    }

    private fun requestLatest(
        path: String,
        parameters: Map<String, Any?>,
        method: String,
    ): RawHttpResponse {
        require(method.equals("POST", ignoreCase = true)) {
            "2.9.365 已验证业务请求必须使用 POST: $path"
        }
        require(LatestProtocol.isAllowedApiPath(path)) {
            "2.9.365 API 路径未在核心白名单中: $path"
        }

        // A missing account used to be silently replaced with the native placeholder
        // (cmw666). Because the server recomputes hashvalue over the *received*
        // account, that substitution made every account-bound request fail with
        // 200001 even though the caller never chose that value. Only a brand-new
        // guest registration legitimately has no account yet.
        val account = parameters["account"]?.toString()?.trim().orEmpty()
        if (account.isEmpty()) {
            require(path.trimStart('/') == "signup/auto_reg_v2") {
                "2.9.365 请求缺少 account，已拒绝以避免使用占位账号: $path"
            }
        }

        val signedParameters = linkedMapOf<String, Any?>().apply {
            putAll(parameters)
            if (account.isEmpty()) put("account", LatestProtocol.DEFAULT_ACCOUNT)
            putIfAbsent("login_token", "")
            putIfAbsent("app_version", config.appVersion)
            putIfAbsent("device_token", config.deviceToken)
        }
        val body = LatestProtocol.buildSignedBody(signedParameters)

        return if (config.transportBackend == CIWEIMAO_TRANSPORT_OFFICIAL_NATIVE) {
            val context = config.nativeContext
                ?: throw IOException("2.9.365 official_native 缺少 Android Context")
            val response = OfficialNativeTransport.post(
                context = context,
                path = path,
                userAgent = config.userAgent(),
                body = body,
                timeoutSeconds = config.timeoutSeconds,
            )
            RawHttpResponse(response.statusCode, response.ciphertext)
        } else {
            requestLatestOkHttp(path, body)
        }
    }

    /** Android network stack; signed request bytes remain unchanged. */
    private fun requestLatestOkHttp(path: String, body: ByteArray): RawHttpResponse {
        val url = CIWEIMAO_LATEST_BASE_URL.toHttpUrl().resolve(path)
            ?: throw IllegalArgumentException("API 路径无效: $path")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("charsets", "utf-8")
            .header("User-Agent", config.userAgent())
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.bytes() ?: ByteArray(0)
            if (payload.size > MAX_API_RESPONSE_BYTES) {
                throw IOException("API 响应超过本地保护上限")
            }
            return RawHttpResponse(response.code, payload.toString(Charsets.US_ASCII))
        }
    }

    private fun requestLegacy(
        path: String,
        parameters: Map<String, Any?>,
        method: String,
    ): RawHttpResponse {
        val base = CIWEIMAO_BASE_URL.toHttpUrl().resolve(path)
            ?: throw IllegalArgumentException("API 路径无效: $path")
        val builder = Request.Builder().header("User-Agent", config.userAgent())
        if (method.equals("POST", ignoreCase = true)) {
            builder.url(base).post(
                FormBody.Builder().apply {
                    parameters.forEach { (key, value) -> add(key, wireValue(value)) }
                }.build(),
            )
        } else {
            builder.url(
                base.newBuilder().apply {
                    parameters.forEach { (key, value) ->
                        addQueryParameter(key, wireValue(value))
                    }
                }.build(),
            ).get()
        }
        client.newCall(builder.build()).execute().use { response ->
            val payload = response.body?.bytes() ?: ByteArray(0)
            if (payload.size > MAX_API_RESPONSE_BYTES) {
                throw IOException("API 响应超过本地保护上限")
            }
            return RawHttpResponse(response.code, payload.toString(Charsets.US_ASCII))
        }
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

    companion object {
        // Local fail-closed guard; it never truncates a valid response.
        private const val MAX_API_RESPONSE_BYTES = 128 * 1024 * 1024
    }
}
