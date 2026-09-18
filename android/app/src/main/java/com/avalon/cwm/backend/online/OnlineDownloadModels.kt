package com.avalon.cwm.backend.online

import com.avalon.cwm.backend.core.ChapterSelection
import org.json.JSONArray
import org.json.JSONObject

data class OnlineDownloadRequest(
    val bookId: String,
    val displayName: String,
    val speed: String,
    val resume: Boolean,
    val selection: ChapterSelection?,
)

data class OnlineDownloadPreset(
    val workers: Int,
    val delayMillis: Long,
)

data class PendingChapter(
    val sequence: Int,
    val title: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sequence", sequence)
        .put("title", title)
}

data class OnlineBookResult(
    val name: String,
    val got: Int,
    val locked: Int,
    val failed: Int,
    val pending: Int,
    val pendingChapters: List<PendingChapter>,
    val resumed: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("got", got)
        .put("locked", locked)
        .put("failed", failed)
        .put("pending", pending)
        .put("pending_chaps", JSONArray(pendingChapters.map { listOf(it.sequence, it.title) }))
        .put("resumed", resumed)

    fun completionMessage(): String = buildString {
        append("完成: ").append(got).append(" 章")
        if (resumed > 0) append(" · 续传复用 ").append(resumed)
        if (locked > 0) append(" · 未购 ").append(locked)
        if (pending > 0) {
            append(" · 未审核 ").append(pending)
            val shown = pendingChapters.take(5)
                .joinToString("、") { "第${it.sequence}章「${it.title}」" }
            if (shown.isNotEmpty()) {
                append("（").append(shown)
                if (pendingChapters.size > 5) append(" 等${pendingChapters.size}章")
                append("）")
            }
        }
        if (failed > 0) append(" · 失败 ").append(failed)
    }
}

val ONLINE_DOWNLOAD_PRESETS = mapOf(
    "safe" to OnlineDownloadPreset(workers = 3, delayMillis = 250),
    "balance" to OnlineDownloadPreset(workers = 8, delayMillis = 0),
    "fast" to OnlineDownloadPreset(workers = 16, delayMillis = 0),
)
