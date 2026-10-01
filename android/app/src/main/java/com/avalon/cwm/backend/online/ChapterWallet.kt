package com.avalon.cwm.backend.online

import java.math.BigDecimal
import org.json.JSONObject

/**
 * Official 2.9.365 MoneyBagActivity/PersonInfoItem display:
 * cookies = rest_hlb - rest_gift_hlb; tokens = rest_gift_hlb.
 * Subscriptions use rest_hlb, which ALREADY includes tokens.
 * Never add the two raw fields. The server chooses the actual debit split.
 */
data class ChapterWallet private constructor(
    val totalHlb: BigDecimal?,
    val giftHlb: BigDecimal?,
    val ordinaryHlb: BigDecimal?,
    val effectiveHlb: BigDecimal?,
    val error: String?,
) {
    fun canAfford(cost: BigDecimal): Boolean = cost.signum() >= 0 &&
        (cost.signum() == 0 || effectiveHlb?.let { it >= cost } == true)

    /** Keep legacy hlb as the inclusive total; use explicit fields for the UI. */
    fun writeTo(output: JSONObject): JSONObject = output
        .put("hlb", totalHlb?.toPlainString() ?: JSONObject.NULL)
        .put("gift_hlb", giftHlb?.toPlainString() ?: JSONObject.NULL)
        .put("ordinary_hlb", ordinaryHlb?.toPlainString() ?: JSONObject.NULL)
        .put("effective_hlb", effectiveHlb?.toPlainString() ?: JSONObject.NULL)
        .put("balance_error", error ?: JSONObject.NULL)

    companion object {
        private val MONEY = Regex("[0-9]+(?:\\.[0-9]+)?")

        fun parseAmount(value: Any?): BigDecimal? {
            if (value == null || value == JSONObject.NULL) return null
            val text = value.toString().trim()
            if (text.length !in 1..64 || !MONEY.matches(text)) return null
            return text.toBigDecimalOrNull()
        }

        fun fromRaw(total: Any?, gift: Any?): ChapterWallet {
            val totalHlb = parseAmount(total)
            val giftHlb = parseAmount(gift)
            val inconsistent = totalHlb != null && giftHlb != null && giftHlb > totalHlb
            val ordinaryHlb = if (totalHlb != null && giftHlb != null && !inconsistent) {
                totalHlb.subtract(giftHlb)
            } else null
            // A known inclusive total is usable even if the split is unknown;
            // never display unknown components as zero or invent a conversion.
            val effectiveHlb = totalHlb.takeUnless { inconsistent }
            val error = when {
                totalHlb == null -> "购章总可用余额缺失或无效"
                inconsistent -> "余额数据不一致：代币超过总额，已禁止自动购章"
                giftHlb == null -> "代币余额未知；总可用余额已读取"
                else -> null
            }
            return ChapterWallet(totalHlb, giftHlb, ordinaryHlb, effectiveHlb, error)
        }
    }
}
