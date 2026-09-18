package com.avalon.cwm.backend.server

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder
import java.util.Locale

object HttpSupport {
    private const val MAX_JSON_BODY_BYTES = 2 * 1024 * 1024
    val BAD_GATEWAY: NanoHTTPD.Response.IStatus = object : NanoHTTPD.Response.IStatus {
        override fun getRequestStatus(): Int = 502
        override fun getDescription(): String = "502 Bad Gateway"
    }

    fun requestJson(session: NanoHTTPD.IHTTPSession): JSONObject {
        if (session.method != NanoHTTPD.Method.POST &&
            session.method != NanoHTTPD.Method.PUT &&
            session.method != NanoHTTPD.Method.PATCH
        ) {
            return JSONObject()
        }
        val contentLength = session.headers.entries
            .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
            ?.value
            ?.toIntOrNull()
            ?: 0
        require(contentLength in 0..MAX_JSON_BODY_BYTES) { "请求体过大" }
        if (contentLength == 0) return JSONObject()

        val output = ByteArrayOutputStream(contentLength)
        val buffer = ByteArray(minOf(16 * 1024, contentLength))
        var remaining = contentLength
        while (remaining > 0) {
            val read = session.inputStream.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        require(remaining == 0) { "请求体读取不完整" }
        val body = output.toByteArray().toString(Charsets.UTF_8).trim()
        return if (body.isEmpty()) JSONObject() else JSONObject(body)
    }

    fun json(
        value: JSONObject,
        status: NanoHTTPD.Response.IStatus = NanoHTTPD.Response.Status.OK,
    ): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(
        status,
        "application/json; charset=utf-8",
        value.toString(),
    ).apply {
        addHeader("Cache-Control", "no-store")
    }

    fun error(
        message: String,
        status: NanoHTTPD.Response.IStatus = NanoHTTPD.Response.Status.BAD_REQUEST,
        extra: JSONObject? = null,
    ): NanoHTTPD.Response {
        val payload = JSONObject().put("ok", false).put("error", message)
        extra?.keys()?.forEach { key -> payload.put(key, extra.opt(key)) }
        return json(payload, status)
    }

    fun html(
        value: String,
        status: NanoHTTPD.Response.IStatus = NanoHTTPD.Response.Status.OK,
    ): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(
        status,
        "text/html; charset=utf-8",
        value,
    )

    fun redirect(location: String): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(
        NanoHTTPD.Response.Status.REDIRECT,
        "text/plain; charset=utf-8",
        "Redirecting",
    ).apply {
        addHeader("Location", location)
        addHeader("Cache-Control", "no-store")
    }

    fun file(
        source: File,
        downloadName: String = source.name,
        mimeType: String = mimeFor(source.name),
        attachment: Boolean = true,
        deleteOnClose: Boolean = false,
        onClose: (() -> Unit)? = null,
    ): NanoHTTPD.Response {
        require(source.isFile) { "文件不存在" }
        val response = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            mimeType,
            if (deleteOnClose || onClose != null) {
                ManagedFileInputStream(source, deleteOnClose, onClose)
            } else {
                FileInputStream(source)
            },
            source.length(),
        )
        if (attachment) {
            response.addHeader("Content-Disposition", contentDisposition(downloadName))
        }
        response.addHeader("Content-Length", source.length().toString())
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    fun bytes(
        data: ByteArray,
        mimeType: String,
        cacheControl: String = "no-store",
    ): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(
        NanoHTTPD.Response.Status.OK,
        mimeType,
        data.inputStream(),
        data.size.toLong(),
    ).apply {
        addHeader("Content-Length", data.size.toString())
        addHeader("Cache-Control", cacheControl)
    }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "txt" -> "text/plain; charset=utf-8"
        "epub" -> "application/epub+zip"
        "zip" -> "application/zip"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    private class ManagedFileInputStream(
        private val source: File,
        private val deleteOnClose: Boolean,
        private val callback: (() -> Unit)?,
    ) : FileInputStream(source) {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                super.close()
            } finally {
                if (deleteOnClose) source.delete()
                callback?.invoke()
            }
        }
    }

    private fun contentDisposition(filename: String): String {
        val asciiFallback = filename.map { character ->
            if (character.code in 0x20..0x7e && character != '"' && character != '\\') {
                character
            } else {
                '_'
            }
        }.joinToString("").ifBlank { "download" }
        val encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$asciiFallback\"; filename*=UTF-8''$encoded"
    }
}
