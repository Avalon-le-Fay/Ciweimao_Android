package com.avalon.cwm.backend.online.latest

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.Locale
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object LatestProtocol {
    const val VERSION = "2.9.366"
    const val BASE_URL = "https://app1.happybooker.cn/"
    const val RESPONSE_SEED = "sD6doAOcW7hm7iaeK6UlcdtAIWlZGlBr"
    const val DEFAULT_ACCOUNT = "cmw666"

    private const val SIGNATURE_KEY = "a90f3731745f1c30ee77cb13fc00005a"
    private const val NORMAL_SUFFIX_366 = "CkMxWNB66668f494e77eeca2ddb052ade358ce6a85"
    private const val RAND_ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val secureRandom = SecureRandom()

    /** 本项目只支持 2.9.366。 */
    fun isLatestVersion(version: String): Boolean = version.trim() == VERSION

    /** Only the core API paths confirmed by the 2.9.365 captures/native strings. */
    fun isAllowedApiPath(path: String): Boolean =
        path.trimStart('/') in ALLOWED_API_PATHS

    fun buildSignedBody(
        parameters: Map<String, Any?>,
        randStr: String = generateRandStr(),
    ): ByteArray {
        require(randStr.length == 16) { "rand_str 必须为 16 个字符" }
        val logical = TreeMap<String, String>()
        parameters.forEach { (key, value) ->
            if (key != "rand_str" && key != "p") logical[key] = wireValue(value)
        }
        logical.putIfAbsent("app_version", VERSION)
        require(logical.containsKey("account")) { "2.9.365 签名请求缺少 account" }

        val encoded = TreeMap<String, String>()
        logical.forEach { (key, value) -> encoded[key] = formEncode(value) }
        val signature = calculateP(
            accountWire = requireNotNull(encoded["account"]),
            appVersionWire = requireNotNull(encoded["app_version"]),
            randStr = randStr,
        )
        val ordinary = encoded.entries.joinToString("&") { (key, value) -> "$key=$value" }
        return "$ordinary&rand_str=$randStr&p=$signature"
            .toByteArray(StandardCharsets.US_ASCII)
    }

    fun generateRandStr(
        nowMillis: Long = System.currentTimeMillis(),
        random16: String? = null,
    ): String {
        val randomPart = random16 ?: buildString(16) {
            repeat(16) { append(RAND_ALPHABET[secureRandom.nextInt(RAND_ALPHABET.length)]) }
        }
        require(randomPart.length == 16 && randomPart.all(RAND_ALPHABET::contains)) {
            "random16 必须是 16 个 0-9A-Za-z 字符"
        }
        val epochSeconds = nowMillis / 1000L
        val combined = randomPart + epochSeconds
        val inner = md5Hex(combined.substring(1, combined.length - 1)).take(12)
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        return String.format(
            Locale.US,
            "%02d%02d%s",
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
            inner,
        )
    }

    fun formEncode(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val output = StringBuilder(bytes.size * 3)
        bytes.forEach { byte ->
            val valueInt = byte.toInt() and 0xff
            when {
                valueInt in 'a'.code..'z'.code ||
                    valueInt in 'A'.code..'Z'.code ||
                    valueInt in '0'.code..'9'.code ||
                    valueInt == '-'.code || valueInt == '.'.code ||
                    valueInt == '_'.code || valueInt == '~'.code -> output.append(valueInt.toChar())
                valueInt == ' '.code -> output.append('+')
                else -> {
                    output.append('%')
                    output.append(UPPER_HEX[valueInt ushr 4])
                    output.append(UPPER_HEX[valueInt and 0x0f])
                }
            }
        }
        return output.toString()
    }

    private fun calculateP(
        accountWire: String,
        appVersionWire: String,
        randStr: String,
    ): String {
        val canonical = buildString {
            append("account=").append(accountWire)
            append("&app_version=").append(appVersionWire)
            append("&rand_str=").append(randStr)
            append("&signatures=").append(SIGNATURE_KEY)
            append(NORMAL_SUFFIX_366)
        }
        require(isLatestVersion(appVersionWire)) { "Unsupported signed protocol version: $appVersionWire" }
        val message = canonical.toByteArray(StandardCharsets.US_ASCII)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SIGNATURE_KEY.toByteArray(StandardCharsets.US_ASCII), "HmacSHA256"))
        return formEncode(base64(mac.doFinal(message)))
    }

    private fun wireValue(value: Any?): String = when (value) {
        null -> ""
        is Boolean -> if (value) "1" else "0"
        else -> value.toString()
    }

    private fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val valueInt = byte.toInt() and 0xff
                append(LOWER_HEX[valueInt ushr 4]).append(LOWER_HEX[valueInt and 0x0f])
            }
        }
    }

    private fun base64(input: ByteArray): String {
        val output = StringBuilder((input.size + 2) / 3 * 4)
        var index = 0
        while (index < input.size) {
            val first = input[index++].toInt() and 0xff
            val hasSecond = index < input.size
            val second = if (hasSecond) input[index++].toInt() and 0xff else 0
            val hasThird = index < input.size
            val third = if (hasThird) input[index++].toInt() and 0xff else 0
            output.append(BASE64[first ushr 2])
            output.append(BASE64[((first and 0x03) shl 4) or (second ushr 4)])
            output.append(if (hasSecond) BASE64[((second and 0x0f) shl 2) or (third ushr 6)] else '=')
            output.append(if (hasThird) BASE64[third and 0x3f] else '=')
        }
        return output.toString()
    }

    private const val UPPER_HEX = "0123456789ABCDEF"
    private const val LOWER_HEX = "0123456789abcdef"
    private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val ALLOWED_API_PATHS = setOf(
        "setting/get_mobile_area",
        "signup/auto_reg_v2",
        "signup/use_geetest",
        "signup/send_verify_code",
        "signup/modify_passwd",
        "signup/login",
        "signup/login_v2",
        "reader/get_my_info",
        "reader/get_prop_info",
        "reader/get_wallet_info",
        "reader/add_readbook",
        "reader/get_task_bonus_with_sign_recommend",
        "reader/get_challenge_task_bonus",
        "reader/get_week_task_chest_bonus",
        "reader/open_novioce_task_chest",
        "bbs/add_bbs_read_time",
        "bbs/share_bbs",
        "bbs/get_bbs_list",
        "bbs/like_bbs",
        "bbs/unlike_bbs",
        "bbs/add_bbs_comment",
        "meta/get_meta_data",
        "setting/get_startpage_url_list",
        "setting/get_check",
        "setting/thired_party_switch",
        "setting/get_version",
        "bookshelf/get_shelf_list",
        "bookshelf/get_shelf_book_list_new",
        "book/get_info_by_id",
        "bookcity/get_filter_search_book_list",
        "bookcity/get_index_list",
        "task/get_sign_record",
        "task/get_all_task_list",
        "chapter/get_updated_chapter_by_division_new",
        "chapter/get_chapter_permission_list",
        "chapter/buy_multi",
        "chapter/get_chapter_download_cmd",
        "chapter/download_cpt",
        "chapter/check_download_cpt",
    )
}
