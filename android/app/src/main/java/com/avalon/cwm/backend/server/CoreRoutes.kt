package com.avalon.cwm.backend.server

import android.content.Context
import com.avalon.cwm.ServerAddressProvider
import com.avalon.cwm.backend.BackendRuntime
import com.avalon.cwm.backend.core.FileTools
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

internal class CoreRoutes(
    private val context: Context,
    private val runtime: BackendRuntime,
    private val accessSessions: AccessSessions,
    private val port: Int,
) : RouteModule {
    private val imageProxy = ImageProxy()

    override fun handle(
        session: NanoHTTPD.IHTTPSession,
        path: String,
    ): NanoHTTPD.Response = when (path) {
        "/login" -> if (accessSessions.isAuthenticated(session.headers["cookie"])) {
            HttpSupport.redirect("/")
        } else {
            HttpSupport.html(LOGIN_PAGE_HTML)
        }
        "/api/login" -> login(session)
        "/api/native/ping" -> HttpSupport.json(
            JSONObject().put("ok", true).put("service", "cwm").put("port", port),
        )
        "/api/network" -> network(session)
        "/api/state" -> state()
        "/api/revision" -> HttpSupport.json(
            JSONObject().put("ok", true).put("revision", runtime.revision.get()),
        )
        "/" -> staticAsset(session, "index.html")
        else -> when {
            path == "/img" -> imageProxy.serve(session)
            path.startsWith("/download/") -> download(path)
            else -> staticAsset(session, path.removePrefix("/"))
        }
    }

    private fun login(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return HttpSupport.error("仅支持 POST", NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED)
        }
        val password = HttpSupport.requestJson(session).optString("password")
        if (!accessSessions.validatePassword(password)) {
            return HttpSupport.error("密码错误", NanoHTTPD.Response.Status.FORBIDDEN)
        }
        return HttpSupport.json(JSONObject().put("ok", true)).apply {
            addHeader("Set-Cookie", accessSessions.setCookieHeader())
        }
    }

    private fun network(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val host = session.headers["host"].orEmpty()
        val origin = if (host.isNotBlank()) "http://$host" else "http://127.0.0.1:$port"
        return HttpSupport.json(
            JSONObject()
                .put("ok", true)
                .put("local_url", "http://127.0.0.1:$port")
                .put("lan_urls", org.json.JSONArray(ServerAddressProvider.lanUrls()))
                .put("current_origin", origin.trimEnd('/'))
                .put("lan_enabled", true),
        )
    }

    private fun state(): NanoHTTPD.Response = HttpSupport.json(
        JSONObject()
            .put("local", localBooksJson(runtime.cache.scanLocalBooks()))
            .put("library", libraryBooksJson(runtime.cache.scanLibrary()))
            .put("outputs", outputsJson(runtime.cache.listOutputs()))
            .put("progress", runtime.localDecrypt.snapshot())
            .put("revision", runtime.revision.get()),
    )

    private fun download(path: String): NanoHTTPD.Response {
        val rawName = path.removePrefix("/download/")
        val name = java.net.URLDecoder.decode(rawName, "UTF-8")
        val file = FileTools.directOutputFile(runtime.paths.output, name)
            ?: return HttpSupport.error("文件不存在", NanoHTTPD.Response.Status.NOT_FOUND)
        if (!file.isFile) return HttpSupport.error("文件不存在", NanoHTTPD.Response.Status.NOT_FOUND)
        return HttpSupport.file(file, file.name)
    }

    /** 每次安装/更新都会变，用来给本地 CSS/JS 做缓存版本。 */
    @Suppress("DEPRECATION")
    private val assetVersion: String by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString(36)
        }.getOrElse { System.currentTimeMillis().toString(36) }
    }

    /** 入口页只改写一次，避免每次请求都对整页 HTML 跑正则。 */
    private val stampedIndex: ByteArray by lazy {
        val raw = context.assets.open("cwm_bundle/web/index.html").use { it.readBytes() }
        AssetVersioning.stamp(String(raw, Charsets.UTF_8), assetVersion).toByteArray(Charsets.UTF_8)
    }

    private fun staticAsset(session: NanoHTTPD.IHTTPSession, relativePath: String): NanoHTTPD.Response {
        val path = relativePath.ifBlank { "index.html" }
        if (path.startsWith('/') || path.contains("..") || path.contains('\\')) {
            return HttpSupport.error("路径无效", NanoHTTPD.Response.Status.FORBIDDEN)
        }
        return try {
            if (path == "index.html") {
                // 入口页永远不缓存，但把带版本号的引用写进去。
                HttpSupport.bytes(stampedIndex, HttpSupport.mimeFor(path))
            } else {
                val bytes = context.assets.open("cwm_bundle/web/$path").use { it.readBytes() }
                // 版本号对得上才发长缓存；否则（旧链接、动态加载的库）不缓存。
                val requested = session.queryParameterString
                    ?.split('&')
                    ?.firstOrNull { it.startsWith("v=") }
                    ?.substring(2)
                val cacheControl = if (requested == assetVersion) {
                    "public, max-age=31536000, immutable"
                } else {
                    "no-store"
                }
                HttpSupport.bytes(bytes, HttpSupport.mimeFor(path), cacheControl)
            }
        } catch (_: Exception) {
            HttpSupport.error("资源不存在", NanoHTTPD.Response.Status.NOT_FOUND)
        }
    }
}
