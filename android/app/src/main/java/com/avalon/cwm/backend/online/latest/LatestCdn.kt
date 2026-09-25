package com.avalon.cwm.backend.online.latest

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * 2.9.365 chapter CDN channel. It is deliberately separate from the signed API
 * channel: no account/login fields are sent to a chapter object URL.
 */
object LatestCdn {
    private const val MAX_REDIRECTS = 4
    private const val MAX_WIRE_BYTES = 16 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 64 * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun fetchText(url: String, userAgent: String): String {
        var current = validateUrl(url)
        repeat(MAX_REDIRECTS) {
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", userAgent)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    val location = response.header("Location")
                        ?: throw IOException("章节 CDN 重定向缺少 Location")
                    current = validateUrl(URL(URL(current), location).toString())
                    return@use
                }
                if (!response.isSuccessful) {
                    throw IOException("章节 CDN HTTP ${response.code}")
                }
                val source = response.body?.source()
                    ?: throw IOException("章节 CDN 响应为空")
                val compressed = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = source.read(buffer)
                    if (count == -1) break
                    if (count == 0) continue
                    if (compressed.size() > MAX_WIRE_BYTES - count) {
                        throw IOException("章节 CDN 响应超过本地保护上限")
                    }
                    compressed.write(buffer, 0, count)
                }
                return inflateUtf8(compressed.toByteArray())
            }
        }
        throw IOException("章节 CDN 重定向次数超过上限")
    }

    private fun validateUrl(value: String): String {
        val url = value.toHttpUrl()
        require(url.scheme == "http" || url.scheme == "https") {
            "章节 CDN 仅允许 HTTP/HTTPS"
        }
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "章节 CDN URL 不允许用户信息"
        }
        val host = url.host.lowercase()
        require(host == "kuangxiangit.com" || host.endsWith(".kuangxiangit.com")) {
            "章节 CDN 主机不在官方允许域内"
        }
        require(url.port == 80 || url.port == 443) {
            "章节 CDN 仅允许标准端口"
        }
        return url.toString()
    }

    private fun inflateUtf8(compressed: ByteArray): String {
        require(compressed.size >= 2 && compressed[0].toInt() and 0xff == 0x78) {
            "章节 CDN 返回的不是已确认的 zlib 封装"
        }
        val inflater = Inflater(false)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        try {
            inflater.setInput(compressed)
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (error: DataFormatException) {
                    throw IOException("章节 CDN zlib 数据无效", error)
                }
                if (count > 0) {
                    if (output.size() > MAX_TEXT_BYTES - count) {
                        throw IOException("章节 CDN 解压结果超过本地保护上限")
                    }
                    output.write(buffer, 0, count)
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw IOException("章节 CDN zlib 数据不完整")
                }
            }
            return output.toByteArray().toString(StandardCharsets.UTF_8)
        } finally {
            inflater.end()
        }
    }
}
