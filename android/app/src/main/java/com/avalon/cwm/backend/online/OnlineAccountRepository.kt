package com.avalon.cwm.backend.online

import com.avalon.cwm.backend.core.JsonFiles
import com.avalon.cwm.backend.core.RuntimePaths
import org.json.JSONObject

class OnlineAccountRepository(private val paths: RuntimePaths) {
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

    private val accountFile = paths.stateFile(".online_account.json")

    @Volatile
    private var cachedClient: CiweimaoClient? = null

    fun load(): JSONObject = JsonFiles.readObject(accountFile) ?: JSONObject()

    fun save(
        account: String,
        loginToken: String,
        appVersion: String,
        deviceContext: DeviceContext,
    ) {
        JsonFiles.write(
            accountFile,
            JSONObject()
                .put("account", account)
                .put("login_token", loginToken)
                .put("app_version", appVersion)
                .put("device_mode", deviceContext.mode)
                .put("device_profile", JSONObject(deviceContext.profile)),
        )
        cachedClient = CiweimaoClient(
            account,
            loginToken,
            clientConfig(appVersion, deviceContext.profile, authRequest = false),
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
        val device = runCatching { normaliseDeviceContext(account) }
            .getOrElse { DeviceContext("compat", emptyMap()) }
        return CiweimaoClient(
            name,
            token,
            clientConfig(
                account.optString("app_version").ifBlank { CIWEIMAO_APP_VERSION },
                device.profile,
                authRequest = false,
            ),
        ).also { cachedClient = it }
    }

    fun installClient(
        identity: JSONObject,
        appVersion: String,
        deviceContext: DeviceContext,
    ): CiweimaoClient {
        val account = identity.getString("account")
        val token = identity.getString("login_token")
        save(account, token, appVersion, deviceContext)
        return requireNotNull(client())
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

    fun authConfig(appVersion: String, profile: Map<String, String>): CiweimaoClientConfig =
        clientConfig(appVersion, profile, authRequest = true)

    private fun clientConfig(
        appVersion: String,
        profile: Map<String, String>,
        authRequest: Boolean,
    ): CiweimaoClientConfig = CiweimaoClientConfig(
        appVersion = appVersion.ifBlank { CIWEIMAO_APP_VERSION },
        timeoutSeconds = if (authRequest) 6 else 25,
        retries = if (authRequest) 1 else 3,
        deviceProfile = profile,
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
    }
}

fun stableOnlineError(error: Throwable): String = when (error) {
    is CiweimaoHttpException -> when (error.kind) {
        "timeout" -> "连接超时，请检查网络后重试"
        "network" -> "无法连接网络，请检查 Wi-Fi 或移动数据后重试"
        else -> "请求失败，请稍后重试"
    }
    is CiweimaoDecryptException -> "服务器响应异常，请稍后重试"
    is CiweimaoApiException -> error.message.orEmpty().ifBlank { "请求失败，请稍后重试" }
    is IllegalArgumentException -> error.message.orEmpty().ifBlank { "请求参数无效" }
    is CiweimaoException -> error.message.orEmpty().ifBlank { "请求失败，请稍后重试" }
    else -> "请求失败，请稍后重试"
}
