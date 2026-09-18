package com.avalon.cwm.backend.server

import android.content.Context
import com.avalon.cwm.backend.BackendRuntime
import fi.iki.elonen.NanoHTTPD
import org.json.JSONException

class NativeHttpServer(
    context: Context,
    port: Int,
    private val nativeToken: String,
    private val reportStartupStage: (String) -> Unit = {},
) : NanoHTTPD("0.0.0.0", port) {
    private val appContext = initialize("获取 Application Context") { context.applicationContext }
    private val runtime = initialize("构造后端运行时") { BackendRuntime(appContext, reportStartupStage) }
    private val sessions = initialize("初始化访问会话") { AccessSessions(appContext) }
    private val coreRoutes = initialize("初始化核心与静态资源路由") { CoreRoutes(appContext, runtime, sessions, port) }
    private val cacheRoutes = initialize("初始化缓存与解密路由") { CacheRoutes(runtime) }
    private val onlineRoutes = initialize("初始化在线路由") { OnlineRoutes(runtime) }
    private val outputRoutes = initialize("初始化输出路由") { OutputRoutes(runtime) }

    private fun <T> initialize(stage: String, block: () -> T): T {
        reportStartupStage(stage)
        return block()
    }

    override fun serve(session: IHTTPSession): Response {
        val path = normalisePath(session.uri)
            ?: return HttpSupport.error("路径无效", Response.Status.FORBIDDEN)
        return try {
            if (!allowedWithoutLogin(path) && !nativeAuthenticated(session) &&
                !sessions.isAuthenticated(session.headers["cookie"])
            ) {
                return if (path.startsWith("/api/")) {
                    HttpSupport.error(
                        "未登录",
                        Response.Status.UNAUTHORIZED,
                        org.json.JSONObject().put("need_login", true),
                    )
                } else {
                    HttpSupport.redirect("/login")
                }
            }
            when {
                path == "/login" || path == "/api/login" -> coreRoutes.handle(session, path)
                path == "/" || !path.startsWith("/api/") -> coreRoutes.handle(session, path)
                path.startsWith("/api/online/") -> onlineRoutes.handle(session, path)
                path.startsWith("/api/outputs/") ||
                    path.startsWith("/api/settings/") ||
                    path == "/api/export_dirs" -> outputRoutes.handle(session, path)
                path.startsWith("/api/sources") ||
                    path == "/api/probe" ||
                    path == "/api/import" ||
                    path == "/api/import/refresh" ||
                    path == "/api/root_import" ||
                    path == "/api/decrypt" ||
                    path == "/api/cache/delete" ||
                    path == "/api/progress" ||
                    path == "/api/library/refresh" -> cacheRoutes.handle(session, path)
                else -> coreRoutes.handle(session, path)
            }
        } catch (error: JSONException) {
            HttpSupport.error("请求 JSON 无效: ${error.message}", Response.Status.BAD_REQUEST)
        } catch (error: IllegalArgumentException) {
            HttpSupport.error(error.message ?: "请求参数无效", Response.Status.BAD_REQUEST)
        } catch (error: IllegalStateException) {
            HttpSupport.error(error.message ?: "当前状态不允许此操作", Response.Status.CONFLICT)
        } catch (error: Throwable) {
            HttpSupport.error(
                error.message?.take(1_000) ?: error.javaClass.simpleName,
                Response.Status.INTERNAL_ERROR,
            )
        }
    }

    private fun nativeAuthenticated(session: IHTTPSession): Boolean {
        if (nativeToken.isBlank()) return false
        val supplied = session.headers["x-cwm-native-token"].orEmpty()
        return java.security.MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            nativeToken.toByteArray(Charsets.UTF_8),
        )
    }

    private fun allowedWithoutLogin(path: String): Boolean =
        path == "/login" ||
            path == "/api/login" ||
            path.startsWith("/lib/") ||
            path.startsWith("/fxdemo")

    private fun normalisePath(raw: String?): String? {
        val path = raw.orEmpty().substringBefore('?').ifBlank { "/" }
        if (!path.startsWith('/') || path.contains('\u0000')) return null
        val components = path.split('/').filter(String::isNotEmpty)
        if (components.any { it == "." || it == ".." || it.contains('\\') }) return null
        return "/" + components.joinToString("/")
    }
}

internal interface RouteModule {
    fun handle(session: NanoHTTPD.IHTTPSession, path: String): NanoHTTPD.Response
}
