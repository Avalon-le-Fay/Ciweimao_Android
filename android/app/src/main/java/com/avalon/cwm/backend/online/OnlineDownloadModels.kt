package com.avalon.cwm.backend.online

import com.avalon.cwm.backend.core.ChapterSelection
import org.json.JSONArray
import org.json.JSONObject

data class OnlineDownloadRequest(
    val bookId: String,
    val displayName: String,
    val resume: Boolean,
    val selection: ChapterSelection?,
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

    /**
     * Human-readable result, one concern per line.
     *
     * The old single line glued every field together with " · " and wrapped
     * badly on a phone: the counters ran together and the account name could
     * be split in the middle of its digits. Each logical group now owns its
     * own line so the task panel and the download log stay readable at any
     * width.
     */
    fun completionMessage(): String {
        val lines = mutableListOf<String>()
        val head = StringBuilder("完成: ").append(got).append(" 章")
        if (resumed > 0) head.append(" - 续传复用 ").append(resumed)
        lines += head.toString()

        val counts = mutableListOf<String>()
        if (locked > 0) counts += "未购 $locked"
        if (pending > 0) counts += "未审核 $pending"
        if (failed > 0) counts += "失败 $failed"
        if (counts.isNotEmpty()) lines += counts.joinToString(" + ")

        if (pending > 0) {
            val shown = pendingChapters.joinToString(",") { it.sequence.toString() }
            if (shown.isNotEmpty()) lines += "第${shown}章 审核未通过"
        }
        return lines.joinToString("\n")
    }
}

/** Official BuyDownThread uses one fixed ten-slot download window. */
const val ONLINE_DOWNLOAD_WORKERS = 10
const val ONLINE_DOWNLOAD_BATCH_SIZE = 10
const val ONLINE_DOWNLOAD_RETRY_LIMIT = 5
