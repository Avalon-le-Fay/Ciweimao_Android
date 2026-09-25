package com.avalon.cwm.backend.server

import com.avalon.cwm.backend.BackendRuntime
import com.avalon.cwm.backend.output.OutputOptions
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

internal class OutputRoutes(
    private val runtime: BackendRuntime,
) : RouteModule {
    override fun handle(
        session: NanoHTTPD.IHTTPSession,
        path: String,
    ): NanoHTTPD.Response = when {
        path == "/api/export_dirs" -> exportDirectories()
        path == "/api/settings/export_dir" && session.method == NanoHTTPD.Method.GET ->
            HttpSupport.json(runtime.outputSettings.settingsJson())
        path == "/api/settings/export_dir" && session.method == NanoHTTPD.Method.POST ->
            saveExportDirectory(session)
        path == "/api/settings/export_cleanup" -> exportCleanup(session)
        path == "/api/settings/fav_dirs" -> HttpSupport.json(
            JSONObject().put("dirs", JSONArray(runtime.outputSettings.favouriteDirectories())),
        )
        path == "/api/settings/fav_dirs/add" -> addFavourite(session)
        path == "/api/settings/fav_dirs/remove" -> removeFavourite(session)
        path == "/api/outputs/delete" -> deleteOutputs(session)
        path == "/api/outputs/export" -> exportOutputs(session)
        path == "/api/outputs/download/prepare" -> prepareDownload(session)
        path.startsWith("/api/outputs/download/file/") -> preparedFile(path)
        path.startsWith("/api/outputs/download/complete/") -> completePrepared(path)
        path == "/api/outputs/download" -> directDownload(session)
        else -> HttpSupport.error("接口不存在", NanoHTTPD.Response.Status.NOT_FOUND)
    }

    private fun exportDirectories(): NanoHTTPD.Response {
        val saved = runtime.outputSettings.exportDirectory()
        return HttpSupport.json(
            JSONObject()
                .put("saved", saved)
                .put("dirs", JSONArray(runtime.outputSettings.suggestions())),
        )
    }

    private fun saveExportDirectory(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val path = HttpSupport.requestJson(session).optString("dir")
        val saved = runtime.outputSettings.saveExportDirectory(path)
        runtime.bumpRevision()
        return HttpSupport.json(JSONObject().put("ok", true).put("dir", saved))
    }

    private fun exportCleanup(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method == NanoHTTPD.Method.GET) {
            return HttpSupport.json(
                JSONObject().put("auto_cleanup", runtime.outputSettings.autoCleanup()),
            )
        }
        val payload = HttpSupport.requestJson(session)
        if (!payload.has("enabled") || payload.opt("enabled") !is Boolean) {
            return HttpSupport.error("enabled 必须是布尔值")
        }
        val enabled = payload.getBoolean("enabled")
        runtime.outputSettings.saveAutoCleanup(enabled)
        runtime.bumpRevision()
        return HttpSupport.json(
            JSONObject().put("ok", true).put("auto_cleanup", enabled),
        )
    }

    private fun addFavourite(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val path = HttpSupport.requestJson(session).optString("dir")
        return HttpSupport.json(
            JSONObject()
                .put("ok", true)
                .put("dirs", JSONArray(runtime.outputSettings.addFavouriteDirectory(path))),
        )
    }

    private fun removeFavourite(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val path = HttpSupport.requestJson(session).optString("dir")
        return HttpSupport.json(
            JSONObject()
                .put("ok", true)
                .put("dirs", JSONArray(runtime.outputSettings.removeFavouriteDirectory(path))),
        )
    }

    private fun deleteOutputs(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val names = jsonArrayValues(HttpSupport.requestJson(session).optJSONArray("names"))
        return HttpSupport.json(runtime.outputs.deleteOutputs(names))
    }

    private fun exportOutputs(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val payload = HttpSupport.requestJson(session)
        val names = jsonArrayValues(payload.optJSONArray("names"))
        val (files, missing) = runtime.outputs.select(names)
        val result = runtime.outputs.exportToBackend(
            files = files,
            failedSelections = missing,
            destinationPath = payload.optString("dst"),
            options = OutputOptions.from(payload),
        )
        return HttpSupport.json(result.toJson())
    }

    private fun prepareDownload(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val payload = HttpSupport.requestJson(session)
        val names = jsonArrayValues(payload.optJSONArray("names"))
        if (names.isEmpty()) return HttpSupport.error("未选择要下载的文件")
        val (files, missing) = runtime.outputs.select(names)
        if (missing.isNotEmpty()) {
            return HttpSupport.error(
                "选中的文件不存在",
                NanoHTTPD.Response.Status.NOT_FOUND,
                JSONObject().put("missing", JSONArray(missing)),
            )
        }
        val (item, optionalMissing) = runtime.outputs.prepareDownload(
            files,
            OutputOptions.from(payload),
        )
        return HttpSupport.json(
            JSONObject()
                .put("ok", true)
                .put("url", "/api/outputs/download/file/${item.token}")
                .put("filename", item.filename)
                .put("optional_missing", JSONArray(optionalMissing)),
        )
    }

    private fun preparedFile(path: String): NanoHTTPD.Response {
        val token = path.substringAfterLast('/').trim()
        val item = runtime.outputs.preparedDownload(token)
            ?: return HttpSupport.error(
                "下载已过期或不存在",
                NanoHTTPD.Response.Status.NOT_FOUND,
            )
        return HttpSupport.file(
            source = item.file,
            downloadName = item.filename,
            mimeType = item.mimeType,
            onClose = { runtime.outputs.preparedTransferClosed(token) },
        )
    }

    private fun completePrepared(path: String): NanoHTTPD.Response {
        val token = path.substringAfterLast('/').trim()
        if (token.isBlank()) return HttpSupport.error("下载令牌无效")
        runtime.outputs.completePreparedDownload(token)
        return HttpSupport.json(JSONObject().put("ok", true))
    }

    private fun directDownload(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val payload = HttpSupport.requestJson(session)
        val names = jsonArrayValues(payload.optJSONArray("names"))
        if (names.isEmpty()) return HttpSupport.error("未选择要下载的文件")
        val (files, missing) = runtime.outputs.select(names)
        if (missing.isNotEmpty()) {
            return HttpSupport.error(
                "选中的文件不存在或不是 output/ 下的直接成品",
                NanoHTTPD.Response.Status.NOT_FOUND,
                JSONObject().put("missing", JSONArray(missing)),
            )
        }
        val options = OutputOptions.from(payload)
        if (files.size == 1 && !options.hasOptionalContent) {
            val selected = files.single()
            return HttpSupport.file(selected.file, selected.name)
        }
        val (archive, optionalMissing) = runtime.outputs.buildArchive(files, options)
        return HttpSupport.file(
            source = archive,
            downloadName = "cwm-outputs.zip",
            mimeType = "application/zip",
            deleteOnClose = true,
        ).apply {
            if (optionalMissing.isNotEmpty()) {
                addHeader("X-Cwm-Optional-Missing", JSONArray(optionalMissing).toString())
            }
        }
    }
}
