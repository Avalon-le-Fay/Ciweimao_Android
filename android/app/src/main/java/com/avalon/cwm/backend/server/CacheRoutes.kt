package com.avalon.cwm.backend.server

import com.avalon.cwm.RootImportGateway
import com.avalon.cwm.backend.BackendRuntime
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

internal class CacheRoutes(
    private val runtime: BackendRuntime,
) : RouteModule {
    override fun handle(
        session: NanoHTTPD.IHTTPSession,
        path: String,
    ): NanoHTTPD.Response = when (path) {
        "/api/sources" -> sources()
        "/api/probe" -> probe(session)
        "/api/sources/fav/add" -> addFavourite(session)
        "/api/sources/fav/remove" -> removeFavourite(session)
        "/api/import" -> importCache(session)
        "/api/import/refresh" -> refreshImport()
        "/api/root_import" -> rootImport(session)
        "/api/decrypt" -> decrypt(session)
        "/api/cache/delete" -> deleteCache(session)
        "/api/progress" -> HttpSupport.json(runtime.localDecrypt.snapshot())
        "/api/library/refresh" -> libraryRefresh()
        else -> HttpSupport.error("接口不存在", NanoHTTPD.Response.Status.NOT_FOUND)
    }

    private fun sources(): NanoHTTPD.Response {
        val saved = runtime.cache.savedCombined()
        val favourites = runtime.cache.favouriteSources()
        val candidates = buildList {
            if (saved.isNotBlank()) add(saved)
            favourites.forEach { if (it != saved) add(it) }
        }
        return HttpSupport.json(
            JSONObject()
                .put("saved", saved)
                .put("saved_split", runtime.cache.savedSplit())
                .put(
                    "sources",
                    JSONArray(candidates.map { runtime.cache.probeCombined(it).toJson() }),
                ),
        )
    }

    private fun probe(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val payload = HttpSupport.requestJson(session)
        val path = payload.optString("path").trim()
        val source = if (path.isNotEmpty()) {
            runtime.cache.probeCombined(path)
        } else {
            val split = splitPaths(payload)
            if (split.any(String::isBlank)) {
                return HttpSupport.error("分散导入需要同时填写数据库、加密章节和密钥三个路径")
            }
            runtime.cache.probeSplit(split[0], split[1], split[2])
        }
        return HttpSupport.json(JSONObject().put("ok", true).put("info", source.toJson()))
    }

    private fun addFavourite(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val path = HttpSupport.requestJson(session).optString("path").trim().trimEnd('/')
        require(path.isNotEmpty()) { "路径不能为空" }
        val values = runtime.cache.favouriteSources()
        if (path !in values) values += path
        return HttpSupport.json(
            JSONObject().put("ok", true).put(
                "dirs",
                JSONArray(runtime.cache.saveFavouriteSources(values)),
            ),
        )
    }

    private fun removeFavourite(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val path = HttpSupport.requestJson(session).optString("path").trim().trimEnd('/')
        val values = runtime.cache.favouriteSources().filterNot { it == path }
        val saved = runtime.cache.saveFavouriteSources(values)
        if (runtime.cache.savedCombined().trimEnd('/') == path) {
            runtime.cache.clearSavedCombined()
        }
        return HttpSupport.json(JSONObject().put("ok", true).put("dirs", JSONArray(saved)))
    }

    private fun importCache(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val payload = HttpSupport.requestJson(session)
        val path = payload.optString("path").trim()
        val result = if (path.isNotEmpty()) {
            runtime.cache.importCombined(path)
        } else {
            val split = splitPaths(payload)
            if (split.any(String::isBlank)) {
                return HttpSupport.error("分散导入需要同时填写数据库、加密章节和密钥三个路径")
            }
            runtime.cache.importSplit(
                split[0],
                split[1],
                split[2],
                payload.optJSONObject("save_paths"),
                sourceMode = payload.optString("source_mode", "split"),
            )
        }
        runtime.bumpRevision()
        return HttpSupport.json(JSONObject().put("ok", true).put("result", result))
    }

    private fun refreshImport(): NanoHTTPD.Response {
        val mode = runtime.cache.sourceMode()
        val result = when (mode) {
            "combined" -> {
                val source = runtime.cache.savedCombined()
                if (source.isBlank()) return HttpSupport.error("没有保存的单目录导入来源")
                runtime.cache.importCombined(source)
            }
            "root_split" -> {
                val saved = runtime.cache.savedSplit()
                val split = splitPaths(saved)
                if (split.any(String::isBlank)) {
                    return HttpSupport.error("没有完整保存的 Root 分散导入路径")
                }
                rootImportAndMerge(split[0], split[1], split[2])
            }
            "split" -> {
                val saved = runtime.cache.savedSplit()
                val split = splitPaths(saved)
                if (split.any(String::isBlank)) {
                    return HttpSupport.error("没有完整保存的分散导入路径")
                }
                val source = runtime.cache.probeSplit(split[0], split[1], split[2])
                if (source.ok) {
                    runtime.cache.importSplit(split[0], split[1], split[2], saved)
                } else {
                    rootImportAndMerge(split[0], split[1], split[2])
                }
            }
            else -> return HttpSupport.error(
                "尚未保存导入来源；请先在「导入缓存」中成功导入一次",
                extra = JSONObject().put("no_source", true),
            )
        }
        runtime.bumpRevision()
        return HttpSupport.json(
            JSONObject()
                .put("ok", true)
                .put("result", result)
                .put("local", localBooksJson(runtime.cache.scanLocalBooks()))
                .put("library", libraryBooksJson(runtime.cache.scanLibrary()))
                .put("source_mode", mode),
        )
    }

    private fun rootImport(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val split = splitPaths(HttpSupport.requestJson(session))
        if (split.any(String::isBlank)) {
            return HttpSupport.error("Root 导入需要同时填写数据库、加密章节和密钥三个路径")
        }
        val result = rootImportAndMerge(split[0], split[1], split[2])
        runtime.bumpRevision()
        return HttpSupport.json(JSONObject().put("ok", true).put("result", result))
    }

    private fun rootImportAndMerge(
        databasePath: String,
        booksPath: String,
        keysPath: String,
    ): JSONObject {
        val staged = JSONObject(
            RootImportGateway.importForHttp(databasePath, booksPath, keysPath),
        )
        if (!staged.optBoolean("ok")) {
            throw IllegalStateException(staged.optString("error", "Root 复制失败"))
        }
        val token = staged.optString("token")
        return try {
            runtime.cache.importSplit(
                staged.optString("db_path"),
                staged.optString("books_path"),
                staged.optString("keys_path"),
                JSONObject()
                    .put("db_path", databasePath)
                    .put("books_path", booksPath)
                    .put("keys_path", keysPath),
                sourceMode = "root_split",
            )
        } finally {
            if (token.isNotBlank()) RootImportGateway.cleanup(token)
        }
    }

    private fun decrypt(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val ids = jsonArrayValues(HttpSupport.requestJson(session).optJSONArray("ids"))
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        if (ids.isEmpty()) return HttpSupport.error("未提供书籍 ID")
        runtime.localDecrypt.enqueue(ids)
        return HttpSupport.json(
            JSONObject().put("ok", true).put("queued", JSONArray(ids)),
        )
    }

    private fun deleteCache(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val ids = jsonArrayValues(HttpSupport.requestJson(session).optJSONArray("ids"))
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
        if (ids.isEmpty()) return HttpSupport.error("未提供书籍 ID")
        val result = runtime.localDecrypt.runWhileIdle(ids) {
            runtime.cache.deleteLocalBooks(ids)
        }
        runtime.bumpRevision()
        return HttpSupport.json(
            result
                .put("ok", true)
                .put("local", localBooksJson(runtime.cache.scanLocalBooks()))
                .put("library", libraryBooksJson(runtime.cache.scanLibrary())),
        )
    }

    private fun libraryRefresh(): NanoHTTPD.Response = HttpSupport.json(
        JSONObject()
            .put("ok", true)
            .put("library", libraryBooksJson(runtime.cache.scanLibrary()))
            .put("local", localBooksJson(runtime.cache.scanLocalBooks())),
    )

    private fun splitPaths(payload: JSONObject): List<String> = listOf(
        payload.optString("db_path").trim(),
        payload.optString("books_path").trim(),
        payload.optString("keys_path").trim(),
    )
}
