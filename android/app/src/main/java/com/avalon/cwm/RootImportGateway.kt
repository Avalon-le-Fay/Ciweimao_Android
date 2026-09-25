package com.avalon.cwm

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Restricted synchronous bridge used by the native Kotlin HTTP server.
 *
 * It exposes no arbitrary shell command: RootCacheImporter accepts user-selected absolute paths,
 * applies path/command-injection checks, and runs its fixed cache copy script only.
 */
object RootImportGateway {
    private const val WAIT_SECONDS = 65L * 60L
    private val importer = RootCacheImporter()

    @Volatile
    private var appContext: Context? = null

    @JvmStatic
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun importForHttp(databasePath: String, booksPath: String, keysPath: String): String {
        val context = appContext
            ?: return failure("Root 网关尚未初始化；请确认 APK 后台服务正在运行")
        val done = CountDownLatch(1)
        var response = failure("Root 导入未完成")

        importer.start(
            context = context,
            databaseInput = databasePath,
            booksInput = booksPath,
            keysInput = keysPath,
            callback = object : RootCacheImporter.Callback {
                override fun onSuccess(session: RootCacheImporter.Session) {
                    response = JSONObject()
                        .put("ok", true)
                        .put("token", session.token)
                        .put("db_path", session.databasePath)
                        .put("books_path", session.booksPath)
                        .put("keys_path", session.keysPath)
                        .toString()
                    done.countDown()
                }

                override fun onFailure(message: String) {
                    response = failure(message)
                    done.countDown()
                }
            },
        )

        if (!done.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            return failure("Root 导入等待超时")
        }
        return response
    }

    @JvmStatic
    fun commitDeleteForHttp(token: String, bookIds: List<String>, keyNames: List<String>): String {
        val done = CountDownLatch(1)
        var response = failure("Root 删除未完成")
        importer.commitDelete(token, bookIds, keyNames, object : RootCacheImporter.DeleteCallback {
            override fun onSuccess() {
                response = JSONObject().put("ok", true).toString()
                done.countDown()
            }

            override fun onFailure(message: String) {
                response = failure(message)
                done.countDown()
            }
        })
        if (!done.await(WAIT_SECONDS, TimeUnit.SECONDS)) return failure("Root 删除等待超时")
        return response
    }

    @JvmStatic
    fun cleanup(token: String) {
        importer.cleanup(token)
    }

    private fun failure(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
