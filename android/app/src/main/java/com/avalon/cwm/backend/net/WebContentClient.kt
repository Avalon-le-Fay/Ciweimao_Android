package com.avalon.cwm.backend.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

class WebContentClient(
    connectTimeoutSeconds: Long = 12,
    readTimeoutSeconds: Long = 25,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(readTimeoutSeconds + connectTimeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun getBytes(url: String): ByteArray {
        require(url.startsWith("https://") || url.startsWith("http://")) { "不支持的 URL" }
        val request = Request.Builder().url(url).apply {
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.bytes() ?: byteArrayOf()
        }
    }

    fun getBookDescription(bookId: Long): String = try {
        val html = getBytes("https://www.ciweimao.com/book/$bookId").toString(Charsets.UTF_8)
        Jsoup.parse(html).selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
    } catch (_: Exception) {
        ""
    }
}
