package com.avalon.cwm.backend.online

import com.avalon.cwm.backend.core.ChapterSelection
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

data class OnlineDownloadRequest(
    val accountId: String = "",
    val bookId: String,
    val displayName: String,
    val resume: Boolean,
    val selection: ChapterSelection?,
    /** single = existing behavior; order/balance enable multi-account purchasing. */
    val accountStrategy: String = "single",
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
    val accountId: String = "",
    val accountLabel: String = "",
    val accountStrategy: String = "single",
    val purchaseCost: BigDecimal? = null,
    val purchaseReport: JSONArray? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("got", got)
        .put("locked", locked)
        .put("failed", failed)
        .put("pending", pending)
        .put("pending_chaps", JSONArray(pendingChapters.map { listOf(it.sequence, it.title) }))
        .put("resumed", resumed)
        .put("account_id", accountId)
        .put("account", accountLabel)
        .put("account_strategy", accountStrategy)
        .put("purchase_cost", purchaseCost?.toPlainString() ?: JSONObject.NULL)
        .put("purchase_report", purchaseReport ?: JSONObject.NULL)

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
        if (accountLabel.isNotBlank()) lines += "账号：$accountLabel"
        if (purchaseCost != null && purchaseCost > BigDecimal.ZERO) {
            lines += "购章预算 ${purchaseCost.stripTrailingZeros().toPlainString()} 币（猫饼干+代币）"
        }
        purchaseReport?.let { report ->
            val groups = (0 until report.length()).mapNotNull { report.optJSONObject(it) }
                .mapNotNull { item ->
                    val ids = item.optJSONArray("chapter_ids") ?: return@mapNotNull null
                    if (ids.length() == 0) return@mapNotNull null
                    val shown = (0 until ids.length()).joinToString(",") { ids.optString(it) }
                    item.optString("account").ifBlank { "账号" } + "：" + shown + "章"
                }
            if (groups.isNotEmpty()) {
                lines += "分组购章"
                lines += groups
            }
        }
        return lines.joinToString("\n")
    }
}

/** Official BuyDownThread uses one fixed ten-slot download window. */
const val ONLINE_DOWNLOAD_WORKERS = 10
const val ONLINE_DOWNLOAD_BATCH_SIZE = 10
const val ONLINE_DOWNLOAD_RETRY_LIMIT = 5
