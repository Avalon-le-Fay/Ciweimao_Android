package com.avalon.cwm.backend.online

import android.content.Context
import android.os.Build
import com.avalon.cwm.backend.online.latest.LatestProtocol

const val CIWEIMAO_DEFAULT_SEED = "zG2nSeEfSHfvTCHy5LCcqtBbQehKNLXn"
/** 366 网关。`requestLegacy` 兜底路径也走这里，避免误用其它网关。 */
const val CIWEIMAO_BASE_URL = "https://app1.happybooker.cn/"
const val CIWEIMAO_APP_VERSION = "2.9.366"
const val CIWEIMAO_LATEST_VERSION = LatestProtocol.VERSION
const val CIWEIMAO_LATEST_BASE_URL = LatestProtocol.BASE_URL
const val CIWEIMAO_DEVICE_TOKEN = "ciweimao_"
const val CIWEIMAO_OK_CODE = "100000"
const val CIWEIMAO_TRANSPORT_OKHTTP = "okhttp"
const val CIWEIMAO_TRANSPORT_OFFICIAL_NATIVE = "official_native"

val CIWEIMAO_AUTH_CODES = setOf("200100", "300001", "301001", "320001")
val CIWEIMAO_UNREVIEWED_MARKERS = listOf(
    "该章节未审核通过",
    "本章节内容未审核通过",
    "章节未审核",
    "内容未审核通过",
    "审核不通过",
)

/**
 * A catalogue entry that is still awaiting review: either the list carries
 * is_valid == 0, or the server substitutes the "该章节未审核通过" placeholder
 * as the chapter title. Such chapters can neither be fetched nor bought.
 */
fun isUnreviewedChapterMeta(title: Any?, isValid: Any?): Boolean {
    if (isValid?.toString()?.trim() == "0") return true
    val text = title?.toString()?.trim().orEmpty()
    if (text.isEmpty()) return false
    return text.length <= 40 && CIWEIMAO_UNREVIEWED_MARKERS.any(text::contains)
}

open class CiweimaoException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class CiweimaoHttpException(
    message: String,
    val kind: String = "network",
    val apiPath: String? = null,
    val causeType: String? = null,
    val retryable: Boolean = kind == "network" || kind == "timeout",
    cause: Throwable? = null,
) : CiweimaoException(message, cause)

class CiweimaoDecryptException(
    message: String,
    cause: Throwable? = null,
) : CiweimaoException(message, cause)

open class CiweimaoApiException(
    val code: String,
    val tip: String,
    val apiPath: String? = null,
) : CiweimaoException("[$code] $tip" + apiPath?.let { " @$it" }.orEmpty())

class CiweimaoAuthException(
    code: String,
    tip: String,
    apiPath: String? = null,
) : CiweimaoApiException(code, tip, apiPath)

class NeedCodeException(
    message: String,
    val codeSent: Boolean = false,
    cause: Throwable? = null,
) : CiweimaoException(message, cause)

/**
 * Versioned protocol configuration.
 *
 * The old 2.9.320 compatibility path remains the default. Exact 2.9.365 uses
 * the captured signed form protocol and the native transport by default. The
 * native Context is kept application-scoped and is never serialized.
 */
data class CiweimaoClientConfig(
    val appVersion: String = CIWEIMAO_APP_VERSION,
    val deviceToken: String = CIWEIMAO_DEVICE_TOKEN,
    val channel: String = if (LatestProtocol.isLatestVersion(appVersion)) "pp" else "Common",
    val timeoutSeconds: Long = 25,
    val seed: String = if (LatestProtocol.isLatestVersion(appVersion)) {
        LatestProtocol.RESPONSE_SEED
    } else {
        CIWEIMAO_DEFAULT_SEED
    },
    val retries: Int = 3,
    val deviceProfile: Map<String, String> = emptyMap(),
    val readerId: String = "",
    val shelfId: String = "",
    val transportBackend: String = if (appVersion.trim() == CIWEIMAO_LATEST_VERSION) {
        CIWEIMAO_TRANSPORT_OFFICIAL_NATIVE
    } else {
        CIWEIMAO_TRANSPORT_OKHTTP
    },
    val nativeContext: Context? = null,
) {
    val isLatest: Boolean
        get() = LatestProtocol.isLatestVersion(appVersion)

    init {
        require(transportBackend == CIWEIMAO_TRANSPORT_OKHTTP ||
            transportBackend == CIWEIMAO_TRANSPORT_OFFICIAL_NATIVE) {
            "未知在线传输后端: $transportBackend"
        }
        require(!isLatest || LatestProtocol.isLatestVersion(appVersion)) {
            "仅支持 2.9.366 签名协议"
        }
        require(transportBackend != CIWEIMAO_TRANSPORT_OFFICIAL_NATIVE || isLatest) {
            "official_native 仅支持 2.9.366"
        }
    }

    fun baseParameters(): LinkedHashMap<String, String> = linkedMapOf(
        "app_version" to appVersion,
        "device_token" to deviceToken,
    ).apply {
        // The official 2.9.365 captures contain no arbitrary device-profile
        // form fields. Device properties are represented only in its User-Agent.
        if (!isLatest) {
            deviceProfile.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) put(key, value)
            }
        }
    }

    fun userAgent(): String {
        if (!isLatest) return "Android com.kuangxiangciweimao.novel $appVersion"
        val brand = profileValue("device_brand", "device_manufacturer")
            .ifBlank { Build.BRAND }
            .ifBlank { Build.MANUFACTURER }
            .ifBlank { "Android" }
        val model = profileValue("device_model")
            .ifBlank { Build.MODEL }
            .ifBlank { "Android device" }
        val sdk = profileValue("sdk_int")
            .ifBlank { Build.VERSION.SDK_INT.toString() }
        val release = profileValue("android_version")
            .ifBlank { Build.VERSION.RELEASE.orEmpty() }
            .ifBlank { "unknown" }
        return "Android  com.kuangxiangciweimao.novel.c  $appVersion, $brand, $model, $sdk, $release"
    }

    private fun profileValue(vararg keys: String): String =
        keys.asSequence()
            .map { deviceProfile[it].orEmpty().trim() }
            .firstOrNull(String::isNotBlank)
            .orEmpty()
}

data class ChapterTextResult(
    val title: String?,
    val content: String?,
    val status: String,
)
