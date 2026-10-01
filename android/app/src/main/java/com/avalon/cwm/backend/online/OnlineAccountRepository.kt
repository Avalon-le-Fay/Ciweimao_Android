package com.avalon.cwm.backend.online

import android.content.Context
import com.avalon.cwm.backend.core.JsonFiles
import com.avalon.cwm.backend.core.RuntimePaths
import com.avalon.cwm.backend.online.latest.LatestProtocol
import org.json.JSONObject

class OnlineAccountRepository(
    paths: RuntimePaths,
    context: Context,
) {
    data class DeviceContext(
        val mode: String,
        val profile: Map<String, String>,
    ) {
        val label: String
            get() = if (mode != "real") {
                "兼容占位"
            } else {
                val model = profile["device_model"].orEmpty().ifBlank { "本机" }
                val version = profile["android_version"].orEmpty()
                if (version.isBlank()) model else "$model · Android $version"
            }
    }

    private val applicationContext = context.applicationContext
    private val accountFile = paths.stateFile(".online_account.json")

    @Volatile
    private var cachedClient: CiweimaoClient? = null

    fun clientFor(account: JSONObject): CiweimaoClient {
        val name = account.optString("account").trim()
        val token = account.optString("login_token").trim()
        require(name.isNotEmpty() && token.isNotEmpty()) { "账号凭据不完整" }
        return createClient(name, token, account)
    }

    /** Activate a stored vault record for compatibility callers. */
    fun activateStored(account: JSONObject): CiweimaoClient {
        save(
            account = account.optString("account").trim(),
            loginToken = account.optString("login_token").trim(),
            appVersion = account.optString("app_version"),
            deviceContext = accountDeviceContext(account),
            readerId = account.optString("reader_id"),
            shelfId = account.optString("shelf_id"),
            deviceToken = account.optString("device_token").ifBlank { CIWEIMAO_DEVICE_TOKEN },
            transportBackend = account.optString("transport_backend").ifBlank { defaultTransport(account.optString("app_version")) },
        )
        return requireNotNull(cachedClient)
    }

    fun load(): JSONObject = JsonFiles.readObject(accountFile) ?: JSONObject()

    fun save(
        account: String,
        loginToken: String,
        appVersion: String,
        deviceContext: DeviceContext,
        readerId: String = "",
        shelfId: String = "",
        deviceToken: String = CIWEIMAO_DEVICE_TOKEN,
        transportBackend: String = defaultTransport(appVersion),
    ) {
        val version = appVersion.ifBlank { CIWEIMAO_APP_VERSION }
        val payload = JSONObject()
            .put("account", account)
            .put("login_token", loginToken)
            .put("app_version", version)
            .put("device_mode", deviceContext.mode)
            .put("device_profile", JSONObject(deviceContext.profile))
        if (LatestProtocol.isLatestVersion(version)) {
            payload.put("reader_id", readerId.trim())
                .put("shelf_id", shelfId.trim())
                .put("device_token", deviceToken.trim().ifBlank { CIWEIMAO_DEVICE_TOKEN })
                .put("transport_backend", transportBackend.trim().ifBlank { defaultTransport(version) })
        }
        JsonFiles.write(accountFile, payload)
        cachedClient = createClient(
            account = account,
            token = loginToken,
            accountPayload = payload,
        )
    }

    fun logout() {
        cachedClient = null
        if (accountFile.exists()) accountFile.delete()
    }

    fun client(): CiweimaoClient? {
        cachedClient?.let { return it }
        val account = load()
        val name = account.optString("account").trim()
        val token = account.optString("login_token").trim()
        if (name.isEmpty() || token.isEmpty()) return null
        return createClient(name, token, account).also { cachedClient = it }
    }

    fun installClient(
        identity: JSONObject,
        appVersion: String,
        deviceContext: DeviceContext,
        transportBackend: String = defaultTransport(appVersion),
    ): CiweimaoClient {
        val account = identity.optString("account").trim()
        val token = identity.optString("login_token").trim()
        require(account.isNotEmpty() && token.isNotEmpty()) {
            "登录响应缺少 account 或 login_token"
        }
        val readerId = identity.optionalString("reader_id")
        val shelfId = identity.optionalString("shelf_id")
        save(
            account = account,
            loginToken = token,
            appVersion = appVersion,
            deviceContext = deviceContext,
            readerId = readerId,
            shelfId = shelfId,
            deviceToken = identity.optString("device_token")
                .ifBlank { CIWEIMAO_DEVICE_TOKEN },
            transportBackend = transportBackend,
        )
        return requireNotNull(cachedClient)
    }

    fun accountDeviceContext(account: JSONObject = load()): DeviceContext =
        runCatching { normaliseDeviceContext(account) }
            .getOrElse { DeviceContext("compat", emptyMap()) }

    fun normaliseDeviceContext(payload: JSONObject): DeviceContext {
        val requested = payload.optString("device_mode", "compat").trim().lowercase()
        val mode = if (requested == "real") "real" else "compat"
        if (mode != "real") return DeviceContext("compat", emptyMap())

        val source = payload.optJSONObject("device_profile")
            ?: throw IllegalArgumentException("本机参数不可用，请切换为兼容占位模式")
        val profile = linkedMapOf<String, String>()
        DEVICE_PROFILE_FIELDS.forEach { key ->
            val value = source.opt(key)
            if (value == null || value == JSONObject.NULL || value is Boolean) return@forEach
            val clean = value.toString()
                .filter { character -> character.code > 0x1f && character.code != 0x7f }
                .trim()
                .take(MAX_DEVICE_PROFILE_VALUE_LENGTH)
            if (clean.isNotEmpty()) profile[key] = clean
        }
        if (!REQUIRED_DEVICE_FIELDS.all(profile::containsKey)) {
            throw IllegalArgumentException("本机参数不完整，请切换为兼容占位模式")
        }
        return DeviceContext("real", profile)
    }

    fun authConfig(
        appVersion: String,
        profile: Map<String, String>,
        readerId: String = "",
        shelfId: String = "",
        deviceToken: String = CIWEIMAO_DEVICE_TOKEN,
        transportBackend: String = defaultTransport(appVersion),
    ): CiweimaoClientConfig = clientConfig(
        appVersion = appVersion,
        profile = profile,
        authRequest = true,
        readerId = readerId,
        shelfId = shelfId,
        deviceToken = deviceToken,
        transportBackend = transportBackend,
    )

    private fun createClient(
        account: String,
        token: String,
        accountPayload: JSONObject,
    ): CiweimaoClient {
        val version = accountPayload.optString("app_version")
            .ifBlank { CIWEIMAO_APP_VERSION }
        val device = accountDeviceContext(accountPayload)
        return CiweimaoClient(
            account,
            token,
            clientConfig(
                appVersion = version,
                profile = device.profile,
                authRequest = false,
                readerId = accountPayload.optString("reader_id"),
                shelfId = accountPayload.optString("shelf_id"),
                deviceToken = accountPayload.optString("device_token")
                    .ifBlank { CIWEIMAO_DEVICE_TOKEN },
                transportBackend = accountPayload.optString("transport_backend")
                    .ifBlank { defaultTransport(version) },
            ),
        )
    }

    private fun clientConfig(
        appVersion: String,
        profile: Map<String, String>,
        authRequest: Boolean,
        readerId: String,
        shelfId: String,
        deviceToken: String,
        transportBackend: String,
    ): CiweimaoClientConfig = CiweimaoClientConfig(
        appVersion = appVersion.ifBlank { CIWEIMAO_APP_VERSION },
        // 认证请求走官方 native libcurl，实测 366 网关单次响应约 2 秒
        // （DNS + TLS + 服务器处理）。移动网络下留足余量避免误报 curl=28。
        // 前端 authApi 的 UI 超时（AUTH_UI_TIMEOUT_MS=25s）略大于此处，
        // 保证后端能先返回真实结果，不会出现「前端已报超时、后端仍在等待」。
        // 下载路径不受影响（仍为 25 秒）。
        timeoutSeconds = if (authRequest) 20 else 25,
        retries = if (authRequest) 1 else 3,
        deviceProfile = profile,
        readerId = readerId.trim(),
        shelfId = shelfId.trim(),
        deviceToken = deviceToken.trim().ifBlank { CIWEIMAO_DEVICE_TOKEN },
        transportBackend = transportBackend.trim().ifBlank {
            defaultTransport(appVersion)
        },
        nativeContext = applicationContext,
    )

    companion object {
        private const val MAX_DEVICE_PROFILE_VALUE_LENGTH = 160
        private val REQUIRED_DEVICE_FIELDS = setOf(
            "device_manufacturer",
            "device_model",
            "android_version",
            "sdk_int",
        )
        private val DEVICE_PROFILE_FIELDS = listOf(
            "device_manufacturer",
            "device_brand",
            "device_model",
            "device_device",
            "device_product",
            "device_hardware",
            "device_board",
            "android_version",
            "sdk_int",
            "build_id",
            "supported_abis",
        )

        @Suppress("UNUSED_PARAMETER")
        fun defaultTransport(appVersion: String): String =
            if (LatestProtocol.isLatestVersion(appVersion)) {
                CIWEIMAO_TRANSPORT_OFFICIAL_NATIVE
            } else {
                CIWEIMAO_TRANSPORT_OKHTTP
            }
    }
}

private fun JSONObject.optionalString(key: String): String {
    val value = opt(key)
    return if (value == null || value == JSONObject.NULL) "" else value.toString().trim()
}

fun stableOnlineError(error: Throwable): String = when (error) {
    is CiweimaoHttpException -> when (error.kind) {
        "timeout" -> error.message.orEmpty().ifBlank { "接口连接超时" }
        "network" -> error.message.orEmpty().ifBlank { "接口连接失败" }
        else -> error.message.orEmpty().ifBlank { "请求失败，请稍后重试" }
    }
    is CiweimaoDecryptException -> "服务器响应异常，请稍后重试"
    is CiweimaoApiException -> error.message.orEmpty().ifBlank { "请求失败，请稍后重试" }
    is IllegalArgumentException -> error.message.orEmpty().ifBlank { "请求参数无效" }
    is CiweimaoException -> error.message.orEmpty().ifBlank { "请求失败，请稍后重试" }
    else -> "请求失败，请稍后重试"
}
