package com.avalon.cwm.backend.online

import org.json.JSONObject

/** Official BaseTaskNew.getC handles 310017 with a real human proof and resumes
 * that exact API. Scope this to the current queue worker and read-only download
 * operations: purchase/login/sign-in must never be replayed by this handler.
 */
internal object DownloadRequestVerification {
    private val handlers = ThreadLocal<(String, CiweimaoClientConfig, String) -> GeetestProof>()
    private val paths = setOf(
        "chapter/get_chapter_download_cmd", "chapter/download_cpt", "chapter/check_download_cpt",
    )

    fun currentHandler(): ((String, CiweimaoClientConfig, String) -> GeetestProof)? = handlers.get()

    fun <T> withHandler(
        handler: (String, CiweimaoClientConfig, String) -> GeetestProof,
        block: () -> T,
    ): T {
        val previous = handlers.get()
        handlers.set(handler)
        return try { block() } finally {
            if (previous == null) handlers.remove() else handlers.set(previous)
        }
    }

    fun <T> withOptionalHandler(
        handler: ((String, CiweimaoClientConfig, String) -> GeetestProof)?,
        block: () -> T,
    ): T = if (handler == null) block() else withHandler(handler, block)

    fun request(
        account: String,
        config: CiweimaoClientConfig,
        path: String,
        parameters: Map<String, Any?>,
        perform: (Map<String, String>) -> JSONObject,
    ): JSONObject {
        try {
            return perform(emptyMap())
        } catch (error: CiweimaoApiException) {
            val handler = handlers.get()
            val cleanPath = path.trimStart('/')
            if (error is CiweimaoAuthException || error.code != "310017" ||
                !config.isLatest || cleanPath !in paths || handler == null ||
                parameters.keys.any { it.startsWith("geetest_") }
            ) throw error
            val proof = handler(account, config, cleanPath)
            // No recursion, task re-enqueue or purchase replay. Exactly one
            // continuation after a real proof; the server validates that proof.
            return try {
                perform(proof.fields())
            } catch (rejected: CiweimaoApiException) {
                if (rejected.code == "310017") throw CiweimaoApiException(
                    rejected.code,
                    "下载验证未被服务器接受，已停止；已购权限保留，不会重新购章",
                    cleanPath,
                )
                throw rejected
            }
        }
    }
}
