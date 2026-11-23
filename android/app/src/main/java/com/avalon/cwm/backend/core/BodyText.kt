package com.avalon.cwm.backend.core

/**
 * 成品正文排版：每段以两个全角空格开头，段与段之间空一行。
 *
 * 站点返回的章节正文本身已是“一行一段、段首两个全角空格”，但段间只有单个换行，
 * 很多阅读器会把它们连成一整块。这里只在写成品时重排，章节缓存仍保留原始正文，
 * 因此改排版后复用旧缓存重新导出也能得到新格式。
 */
internal fun formatBodyParagraphs(raw: String): String {
    if (raw.isEmpty()) return ""
    val paragraphs = raw
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
    if (paragraphs.isEmpty()) return ""
    return paragraphs.joinToString("\n\n") { "　　$it" }
}
