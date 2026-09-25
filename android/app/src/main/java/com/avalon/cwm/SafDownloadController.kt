package com.avalon.cwm

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

class SafDownloadController(
    context: Context,
    private val nativeToken: String,
    private val onEvent: (JSONObject) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "cwm-saf-download")
    }
    private val baseUrl = URL("http://127.0.0.1:8080/")

    fun downloadOutputs(treeUri: Uri, namesJson: String, optionsJson: String) {
        worker.execute {
            try {
                val names = parseNames(namesJson)
                val options = runCatching { JSONObject(optionsJson) }.getOrDefault(JSONObject())
                require(names.isNotEmpty()) { "未选择要下载的文件" }
                onEvent(JSONObject().put("type", "started").put("count", names.size).put("batch", true))
                val saved = if (options.optBoolean("chapters") || options.optBoolean("images")) {
                    val prepared = prepareArchive(names, options)
                    val filename = prepared.optString("filename", "cwm-outputs.zip")
                    listOf(saveUrl(treeUri, prepared.getString("url"), filename, "application/zip"))
                        .also { runCatching { confirmPrepared(prepared.getString("url")) } }
                } else {
                    names.map { name ->
                        saveUrl(
                            treeUri,
                            "/download/${encodePathSegment(name)}",
                            name,
                            mimeForName(name),
                        )
                    }
                }
                onEvent(
                    JSONObject()
                        .put("type", "completed")
                        .put("files", JSONArray(saved))
                        .put("batch", true)
                        .put("clear_selection", true)
                        .put("directory", directoryLabel(treeUri)),
                )
            } catch (error: Throwable) {
                onEvent(
                    JSONObject()
                        .put("type", "error")
                        .put("batch", true)
                        .put("error", error.message?.take(1_000) ?: error.javaClass.simpleName),
                )
            }
        }
    }

    fun downloadDirect(
        treeUri: Uri,
        url: String,
        filename: String,
        mimeType: String?,
    ) {
        worker.execute {
            try {
                onEvent(JSONObject().put("type", "started").put("count", 1).put("batch", false))
                val saved = saveUrl(treeUri, url, filename, mimeType ?: mimeForName(filename))
                preparedToken(url)?.let { runCatching { confirmPreparedToken(it) } }
                onEvent(
                    JSONObject()
                        .put("type", "completed")
                        .put("files", JSONArray(listOf(saved)))
                        .put("batch", false)
                        .put("clear_selection", false)
                        .put("directory", directoryLabel(treeUri)),
                )
            } catch (error: Throwable) {
                onEvent(
                    JSONObject()
                        .put("type", "error")
                        .put("batch", false)
                        .put("error", error.message?.take(1_000) ?: error.javaClass.simpleName),
                )
            }
        }
    }

    fun directoryLabel(treeUri: Uri): String {
        val tree = DocumentFile.fromTreeUri(appContext, treeUri)
        return tree?.name?.takeIf { it.isNotBlank() } ?: "已选择目录"
    }

    private fun prepareArchive(names: List<String>, options: JSONObject): JSONObject {
        val payload = JSONObject().put("names", JSONArray(names)).put("options", options)
        val connection = open("/api/outputs/download/prepare", "POST")
        return try {
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.doOutput = true
            connection.outputStream.use { it.write(bytes) }
            val response = readResponse(connection)
            val json = JSONObject(response)
            require(json.optBoolean("ok")) { json.optString("error", "准备下载失败") }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun saveUrl(
        treeUri: Uri,
        relativeUrl: String,
        requestedName: String,
        mimeType: String,
    ): String {
        val tree = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: error("下载目录授权已失效，请重新选择目录")
        require(tree.isDirectory && tree.canWrite()) { "下载目录不可写，请重新选择目录" }
        val safeName = requestedName.replace('/', ' ').replace(92.toChar(), ' ').trim()
            .ifBlank { "download" }
        val targetName = uniqueName(tree, safeName)
        val target = tree.createFile(mimeType, targetName)
            ?: error("无法在所选目录创建文件")
        val connection = open(relativeUrl, "GET")
        try {
            if (connection.responseCode !in 200..299) {
                error("下载请求失败：HTTP ${connection.responseCode}")
            }
            appContext.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                connection.inputStream.use { input -> input.copyTo(output) }
                output.flush()
            } ?: error("无法写入所选目录")
            return target.name ?: targetName
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun uniqueName(directory: DocumentFile, requested: String): String {
        if (directory.findFile(requested) == null) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var index = 1
        while (index < 10_000) {
            val candidate = "$base-$index$extension"
            if (directory.findFile(candidate) == null) return candidate
            index += 1
        }
        error("同名文件过多")
    }

    private fun open(relativeUrl: String, method: String): HttpURLConnection {
        val url = if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            URL(relativeUrl)
        } else {
            URL(baseUrl, relativeUrl)
        }
        require(url.protocol == "http" && url.host == "127.0.0.1" && url.port == 8080) {
            "已拒绝非本地服务下载"
        }
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            useCaches = false
            setRequestProperty("X-CWM-Native-Token", nativeToken)
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun confirmPrepared(preparedUrl: String) {
        preparedToken(preparedUrl)?.let(::confirmPreparedToken)
    }

    private fun confirmPreparedToken(token: String) {
        val connection = open("/api/outputs/download/complete/$token", "POST")
        try {
            connection.setFixedLengthStreamingMode(0)
            connection.doOutput = true
            connection.outputStream.use { }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun preparedToken(url: String): String? {
        val marker = "/api/outputs/download/file/"
        if (!url.startsWith(marker)) return null
        return url.removePrefix(marker).substringBefore('/').takeIf { it.isNotBlank() }
    }

    private fun parseNames(value: String): List<String> {
        val array = JSONArray(value)
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun mimeForName(name: String): String = when (
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    ) {
        "txt" -> "text/plain"
        "epub" -> "application/epub+zip"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    override fun close() {
        worker.shutdownNow()
    }
}
