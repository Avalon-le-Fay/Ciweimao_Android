package com.avalon.cwm.backend.online

import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Allows one live public verification session at a time. Request credentials
 * and the download command remain in the worker stack, never in a public snapshot;
 * concurrent batch workers queue behind the single verification slot.
 */
internal class DownloadVerificationGate(
    register: (String, CiweimaoClientConfig) -> JSONObject = GeetestRegistration::load,
    private val waitMillis: Long = GeetestSessions.TTL_MILLIS,
    private val operation: String = "下载",
    private val preservedContext: String = "已购权限保留",
) {
    private class Pending(
        val account: String,
        val config: CiweimaoClientConfig,
        val options: JSONObject,
    ) {
        val latch = CountDownLatch(1)
        var proof: GeetestProof? = null
        var cancelled = false
    }
    private val lock = Any()
    private val sessions = GeetestSessions(register)
    // Several batch threads may encounter 310017 together, but the public task
    // protocol has one verification slot. Queue later waiters instead of making
    // them race the single pending session.
    private val verificationSlot = Semaphore(1, true)
    private var pending: Pending? = null

    fun awaitProof(
        account: String,
        config: CiweimaoClientConfig,
        apiPath: String,
        publish: (JSONObject) -> Unit,
    ): GeetestProof {
        require(waitMillis > 0 && waitMillis <= GeetestSessions.TTL_MILLIS)
        try {
            verificationSlot.acquire()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CiweimaoException("${operation}验证等待被中断；$preservedContext", error)
        }
        try {
            val options = sessions.begin(account, config).put("api_path", apiPath)
            val item = Pending(account, config, options)
            synchronized(lock) { pending = item }
            try {
                publish(JSONObject(options.toString()))
                if (!item.latch.await(waitMillis, TimeUnit.MILLISECONDS)) {
                    throw CiweimaoException("${operation}人机验证已超时；$preservedContext")
                }
                return synchronized(lock) {
                    if (item.cancelled) throw CiweimaoException("已取消${operation}人机验证；$preservedContext")
                    item.proof ?: throw CiweimaoException("${operation}验证未完成，未继续请求")
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CiweimaoException("${operation}验证等待被中断；$preservedContext", error)
            } finally {
                synchronized(lock) { if (pending === item) pending = null }
                sessions.clear()
            }
        } finally {
            verificationSlot.release()
        }
    }

    fun submit(sessionId: String, proof: JSONObject?, cancel: Boolean): JSONObject = synchronized(lock) {
        val item = pending ?: throw IllegalStateException("当前没有等待中的${operation}验证")
        require(sessionId == item.options.getString("session_id")) { "${operation}验证会话不匹配" }
        check(item.latch.count != 0L) { "此${operation}验证已经处理" }
        if (cancel) {
            item.cancelled = true
        } else {
            require(proof != null && proof.optString("session_id") == sessionId) { "${operation}验证回调缺失或不匹配" }
            item.proof = sessions.consume(item.account, item.config, proof)
                ?: throw IllegalArgumentException("缺少${operation}验证结果")
        }
        item.latch.countDown()
        JSONObject().put("ok", true).put("accepted", !cancel).put("cancelled", cancel)
    }
}
