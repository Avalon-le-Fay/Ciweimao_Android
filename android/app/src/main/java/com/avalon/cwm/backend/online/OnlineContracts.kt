package com.avalon.cwm.backend.online

const val CIWEIMAO_DEFAULT_SEED = "zG2nSeEfSHfvTCHy5LCcqtBbQehKNLXn"
const val CIWEIMAO_BASE_URL = "https://app.hbooker.com/"
const val CIWEIMAO_APP_VERSION = "2.9.320"
const val CIWEIMAO_DEVICE_TOKEN = "ciweimao_"
const val CIWEIMAO_OK_CODE = "100000"

val CIWEIMAO_AUTH_CODES = setOf("200100", "300001", "301001", "320001")
val CIWEIMAO_UNREVIEWED_MARKERS = listOf(
    "该章节未审核通过",
    "本章节内容未审核通过",
    "章节未审核",
    "内容未审核通过",
    "审核不通过",
)

open class CiweimaoException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class CiweimaoHttpException(
    message: String,
    val kind: String = "network",
    val apiPath: String? = null,
    val causeType: String? = null,
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

class NeedCodeException(message: String) : CiweimaoException(message)

data class CiweimaoClientConfig(
    val appVersion: String = CIWEIMAO_APP_VERSION,
    val deviceToken: String = CIWEIMAO_DEVICE_TOKEN,
    val channel: String = "Common",
    val timeoutSeconds: Long = 25,
    val seed: String = CIWEIMAO_DEFAULT_SEED,
    val retries: Int = 3,
    val deviceProfile: Map<String, String> = emptyMap(),
) {
    fun baseParameters(): LinkedHashMap<String, String> = linkedMapOf(
        "app_version" to appVersion,
        "device_token" to deviceToken,
    ).apply {
        deviceProfile.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) put(key, value)
        }
    }

    fun userAgent(): String = "Android com.kuangxiangciweimao.novel $appVersion"
}

data class ChapterTextResult(
    val title: String?,
    val content: String?,
    val status: String,
)
