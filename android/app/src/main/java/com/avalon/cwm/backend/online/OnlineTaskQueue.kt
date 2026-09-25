package com.avalon.cwm.backend.online

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors

class OnlineTaskQueue(
    private val downloader: OnlineBookDownloader,
    private val onOutputsChanged: () -> Unit,
) {
    private data class TaskRecord(
        val request: OnlineDownloadRequest,
        val taskId: String = UUID.randomUUID().toString(),
        var verification: JSONObject? = null,
        var status: String = "queued",
        var percent: Int = 0,
        var chaptersDone: Int = 0,
        var chaptersTotal: Int = 0,
        var message: String = "排队中...",
        var error: String? = null,
        var resolvedName: String = request.displayName,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("task_id", taskId)
            .put("verification", verification?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            .put("account_id", request.accountId)
            .put("account_strategy", request.accountStrategy)
            .put("book_id", request.bookId)
            .put("name", resolvedName)
            .put("status", status)
            .put("percent", percent)
            .put("chap_done", chaptersDone)
            .put("chap_total", chaptersTotal)
            .put("message", message)
            .put("error", error ?: JSONObject.NULL)
            .put("ts", timestamp / 1000.0)
    }

    private val lock = Any()
    private val verificationGate = DownloadVerificationGate()
    private val waiting = ArrayDeque<TaskRecord>()
    private val records = mutableListOf<TaskRecord>()
    private val completedNames = mutableListOf<String>()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cwm-online-books")
    }

    private var running = false
    private var current: TaskRecord? = null
    private var queueTotal = 0
    private var queueIndex = 0
    private var startedAt = 0L

    fun enqueue(request: OnlineDownloadRequest): JSONObject {
        require(request.bookId.isNotBlank()) { "书籍 ID 为空" }
        var shouldStart = false
        var position = 0
        synchronized(lock) {
            require(current?.let { it.request.accountId != request.accountId || it.request.bookId != request.bookId } ?: true || !running) { "该账号的这本书正在下载中" }
            require(waiting.none { it.request.accountId == request.accountId && it.request.bookId == request.bookId }) { "该账号的这本书已在队列中" }
            val record = TaskRecord(request)
            records += record
            waiting += record
            shouldStart = !running
            if (shouldStart) {
                running = true
                queueTotal = 1
                queueIndex = 0
                startedAt = System.currentTimeMillis()
            } else {
                queueTotal += 1
            }
            position = queueTotal
        }
        if (shouldStart) worker.execute(::drain)
        return JSONObject()
            .put("ok", true)
            .put("queued", !shouldStart)
            .apply { if (!shouldStart) put("position", position) }
    }

    fun isRunning(): Boolean = synchronized(lock) { running }

    fun progressSnapshot(): JSONObject = synchronized(lock) {
        val active = current
        JSONObject()
            .put("running", running)
            .put("account_id", active?.request?.accountId.orEmpty())
            .put("book_id", active?.request?.bookId ?: JSONObject.NULL)
            .put("percent", active?.percent ?: 0)
            .put("message", active?.message.orEmpty())
            .put("done", JSONArray(completedNames))
            .put("error", active?.error ?: JSONObject.NULL)
            .put("chap_done", active?.chaptersDone ?: 0)
            .put("chap_total", active?.chaptersTotal ?: 0)
            .put("started_at", if (startedAt == 0L) 0 else startedAt / 1000.0)
            .put("queue", JSONArray(waiting.map { it.request.bookId }))
            .put("queue_total", queueTotal)
            .put("queue_index", queueIndex)
    }

    fun tasksSnapshot(): JSONObject = synchronized(lock) {
        JSONObject()
            .put("running", running)
            .put("tasks", JSONArray(records.map(TaskRecord::toJson)))
    }

    fun clearFinished() {
        synchronized(lock) {
            records.removeAll { it.status != "queued" && it.status != "running" && it.status != "verification" }
        }
    }

    fun submitVerification(payload: JSONObject): JSONObject {
        synchronized(lock) {
            val active = current ?: throw IllegalStateException("当前没有下载任务")
            require(running && active.taskId == payload.optString("task_id") && active.status == "verification") {
                "下载任务已变化，请刷新任务状态"
            }
            require(active.verification?.optString("session_id") == payload.optString("session_id")) {
                "下载验证会话与任务不匹配"
            }
        }
        return verificationGate.submit(payload.optString("session_id"), payload.optJSONObject("geetest"),
            payload.optBoolean("cancel", false))
    }

    private fun awaitDownloadProof(
        record: TaskRecord,
        account: String,
        config: CiweimaoClientConfig,
        apiPath: String,
    ): GeetestProof {
        val proof = verificationGate.awaitProof(account, config, apiPath) { options ->
            synchronized(lock) {
                check(current === record && running) { "下载任务已结束" }
                record.verification = options
                record.status = "verification"
                record.message = "下载请求需要人工验证；请完成弹出的验证，已购权限保留"
            }
        }
        synchronized(lock) {
            record.verification = null
            record.status = "running"
            record.message = "验证已提交，继续原下载请求（不重新购章）"
        }
        return proof
    }

    private fun drain() {
        while (true) {
            val record = synchronized(lock) {
                if (waiting.isEmpty()) {
                    running = false
                    queueIndex = queueTotal
                    return
                }
                waiting.removeFirst().also { next ->
                    current = next
                    startedAt = System.currentTimeMillis()
                    next.status = "running"
                    next.message = "准备中..."
                    next.error = null
                    queueIndex = queueTotal - waiting.size
                }
            }
            try {
                val result = DownloadRequestVerification.withHandler(
                    handler = { account, config, path -> awaitDownloadProof(record, account, config, path) },
                ) {
                    downloader.download(record.request) { progress ->
                        synchronized(lock) {
                            record.chaptersDone = progress.done
                            record.chaptersTotal = progress.total
                            record.percent = if (progress.total <= 0) 0 else progress.done * 100 / progress.total
                            record.message = progress.message
                        }
                    }
                }
                synchronized(lock) {
                    val message = result.completionMessage()
                    record.status = "done"
                    record.percent = 100
                    record.message = message
                    record.resolvedName = result.name
                    completedNames += result.name
                }
                onOutputsChanged()
            } catch (error: Throwable) {
                val detail = error.message?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: error.javaClass.simpleName
                synchronized(lock) {
                    record.status = "error"
                    record.verification = null
                    record.error = detail
                    record.message = "失败: $detail"
                }
            }
        }
    }
}
