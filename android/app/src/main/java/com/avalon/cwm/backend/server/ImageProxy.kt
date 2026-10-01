package com.avalon.cwm.backend.server

import com.avalon.cwm.backend.core.FileTools
import fi.iki.elonen.NanoHTTPD
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

internal class ImageProxy {
    private data class CachedImage(val mimeType: String, val bytes: ByteArray)

    private val cache = object : LinkedHashMap<String, CachedImage>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedImage>?): Boolean =
            size > MAX_CACHE_ITEMS
    }
    private val client = OkHttpClient.Builder()
        .dns(PublicOnlyDns)
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val raw = session.parameters["u"]?.firstOrNull().orEmpty()
        val url = raw
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return HttpSupport.error("图片 URL 无效")
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return HttpSupport.error("图片 URL 无效")
        }
        synchronized(cache) {
            cache[url]?.let { cached ->
                return HttpSupport.bytes(
                    cached.bytes,
                    cached.mimeType,
                    cacheControl = "public, max-age=604800",
                )
            }
        }

        val image = try {
            download(url)
        } catch (_: SecurityException) {
            return HttpSupport.error("禁止代理本机或内网地址", NanoHTTPD.Response.Status.FORBIDDEN)
        } catch (_: Throwable) {
            return HttpSupport.error("图片下载失败", HttpSupport.BAD_GATEWAY)
        }
        synchronized(cache) { cache[url] = image }
        return HttpSupport.bytes(
            image.bytes,
            image.mimeType,
            cacheControl = "public, max-age=604800",
        )
    }

    private fun download(url: String): CachedImage {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("响应为空")
            if (body.contentLength() > MAX_IMAGE_BYTES) throw IllegalStateException("图片过大")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) throw IllegalStateException("图片过大")
                    output.write(buffer, 0, read)
                }
            }
            val bytes = output.toByteArray()
            val mime = runCatching { FileTools.detectImage(bytes).mime }
                .getOrDefault("image/jpeg")
            return CachedImage(mime, bytes)
        }
    }

    private object PublicOnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.equals("localhost", true) || hostname.equals("ip6-localhost", true)) {
                throw SecurityException("Local host rejected")
            }
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !isPublic(it) }) {
                throw SecurityException("Non-public address rejected")
            }
            return addresses
        }

        private fun isPublic(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress ||
                address.isLinkLocalAddress || address.isSiteLocalAddress ||
                address.isMulticastAddress
            ) {
                return false
            }
            val bytes = address.address
            if (bytes.size == 4) {
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                if (first == 0 || first >= 224) return false
                if (first == 100 && second in 64..127) return false
                if (first == 192 && second == 0) return false
                if (first == 198 && second in 18..19) return false
            }
            if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false
            return true
        }
    }

    companion object {
        private const val MAX_CACHE_ITEMS = 120
        private const val MAX_IMAGE_BYTES = 5L * 1024L * 1024L
    }
}
