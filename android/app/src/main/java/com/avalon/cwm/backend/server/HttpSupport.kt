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

    private class JsonRequestSession(
        private val delegate: NanoHTTPD.IHTTPSession,
        val payload: JSONObject,
    ) : NanoHTTPD.IHTTPSession by delegate

    /** Drain the JSON body once BEFORE routing. NanoHTTPD 2.3.1 reuses the
     * connection but does not discard unread bodies after serve(). Even routes
     * such as run_all/refresh_balances that ignore '{}' must consume its bytes.
     * Cache only in this request wrapper, never on the reusable HTTPSession.
     */
    fun prepareJsonRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.IHTTPSession =
        if (session is JsonRequestSession) session else JsonRequestSession(session, readJsonBody(session))

    fun requestJson(session: NanoHTTPD.IHTTPSession): JSONObject =
        if (session is JsonRequestSession) session.payload else readJsonBody(session)

    private fun readJsonBody(session: NanoHTTPD.IHTTPSession): JSONObject {
        val transferEncoding = session.headers.entries
            .firstOrNull { it.key.equals("transfer-encoding", ignoreCase = true) }?.value.orEmpty()
        require(transferEncoding.isBlank()) { "本地 JSON 接口不支持分块请求体" }
        val lengthText = session.headers.entries
            .firstOrNull { it.key.equals("content-length", ignoreCase = true) }?.value
        val contentLength = if (lengthText == null) 0 else {
            lengthText.trim().toIntOrNull() ?: throw IllegalArgumentException("请求体长度无效")
        }
        require(contentLength in 0..MAX_JSON_BODY_BYTES) { "请求体长度无效或过大" }
        if (contentLength == 0) return JSONObject()
        require(session.method in setOf(NanoHTTPD.Method.POST, NanoHTTPD.Method.PUT,
            NanoHTTPD.Method.PATCH, NanoHTTPD.Method.DELETE)) { "此请求方法不接受请求体" }
        val output = ByteArrayOutputStream(contentLength)
        val buffer = ByteArray(minOf(16 * 1024, contentLength))
        var remaining = contentLength
        while (remaining > 0) {
            val read = session.inputStream.read(buffer, 0, minOf(buffer.size, remaining))
            require(read > 0) { "请求体读取不完整" }
            output.write(buffer, 0, read)
            remaining -= read
        }
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
