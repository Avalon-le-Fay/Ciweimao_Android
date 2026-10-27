package com.avalon.cwm.backend.online

/**
 * 章节编号与“未审核”占位章在成品里的呈现规则。
 *
 * 目录标题里印着的编号才是读者认得的编号；TOC 序号只是位置索引，遇到前置章、
 * 跳号或“该章节未审核通过”占位章就会整体偏移——这正是“选了 138-152 却导出成
 * 节选 141-155”的根因。未审核占位章自己没有编号，但只要附近编号与位置同步推进，
 * 就能把它推断出来（165 夹在 164 与 166 之间）。
 */

/**
 * The chapter number printed in a chapter title.
 *
 * Titles arrive in two shapes: "138.所谓的半身" and "第一百三十八章:...".
 * This is the number the reader actually sees and picks in the picker, so it
 * is the only one that can match the finished file's name.
 */
internal fun titleChapterNumber(title: String): Int? {
    val text = title.trim()
    if (text.isEmpty()) return null
    Regex("^(\\d{1,5})\\s*[.、,，:：]").find(text)?.let { match ->
        return match.groupValues[1].toIntOrNull()
    }
    val chinese = Regex("^第([零一二三四五六七八九十百千两0-9]{1,12})[章节回]").find(text)
        ?: return null
    val raw = chinese.groupValues[1]
    raw.toIntOrNull()?.let { return it }
    return chineseNumber(raw)
}

/** 一百三十八 -> 138. Returns null when the text is not a plain number. */
internal fun chineseNumber(raw: String): Int? {
    val digits = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    var total = 0
    var pending = 0
    for (ch in raw) {
        when {
            digits.containsKey(ch) -> pending = digits.getValue(ch)
            ch == '十' -> { total += (if (pending == 0) 1 else pending) * 10; pending = 0 }
            ch == '百' -> { total += (if (pending == 0) 1 else pending) * 100; pending = 0 }
            ch == '千' -> { total += (if (pending == 0) 1 else pending) * 1000; pending = 0 }
            else -> return null
        }
    }
    return total + pending
}

/**
 * 给没有编号的条目补号：只有当一段连续缺号的前后两侧都有编号、且“编号推进”
 * 与“位置推进”完全同步时才补，避免把真的跳号（缺号）书补错。
 */
internal fun inferChapterNumbers(numbers: List<Int?>): List<Int?> {
    if (numbers.none { it != null }) return numbers
    val result = numbers.toMutableList()
    var index = 0
    while (index < result.size) {
        if (result[index] != null) {
            index += 1
            continue
        }
        var end = index
        while (end < result.size && result[end] == null) end += 1
        val before = index - 1
        val after = end
        val beforeNumber = if (before >= 0) result[before] else null
        val afterNumber = if (after < result.size) result[after] else null
        if (beforeNumber != null && afterNumber != null &&
            afterNumber - beforeNumber == after - before
        ) {
            for (position in index until end) {
                result[position] = beforeNumber + (position - before)
            }
        }
        index = end
    }
    return result
}

/** 每章用于展示的编号：displayNumber 优先，其次标题编号，最后按缺口推断。 */
internal fun chapterDisplayNumbers(chapters: List<OrderedOnlineChapter>): List<Int?> =
    inferChapterNumbers(chapters.map { it.displayNumber ?: titleChapterNumber(it.fallbackTitle) })

/** 单章展示编号：推断值 -> 标题编号 -> TOC 序号（最后兜底）。 */
internal fun chapterDisplayNumber(chapter: OrderedOnlineChapter): Int =
    chapter.displayNumber ?: titleChapterNumber(chapter.fallbackTitle) ?: chapter.sequence

/**
 * "138-152" for the selected chapters, or null when nothing is selected.
 *
 * 标题里的印号优先；只有当整批标题都没有编号时才退回 TOC 位置序号。
 * 未审核占位章没有标题编号，但不会再让整批退回序号——那正是文件名错成
 * “节选 157-186”（实际 154-183）的原因。
 */
internal fun excerptSpan(selected: List<OrderedOnlineChapter>): String? {
    if (selected.isEmpty()) return null
    val printed = chapterDisplayNumbers(selected).filterNotNull()
    val first: Int
    val last: Int
    if (printed.isNotEmpty()) {
        first = printed.min()
        last = printed.max()
    } else {
        first = selected.minOf { it.sequence }
        last = selected.maxOf { it.sequence }
    }
    return if (first == last) "$first" else "$first-$last"
}

/**
 * 未审核章写进成品时的标题：原样使用目录里的原标题字段，并补上目录推断出的章号。
 *
 * 官方对未审核章只给占位文本、自身不带编号（编号只印在正常章节标题里，如“185.Mortis”）；
 * “184”这类章号来自 chapterDisplayNumbers 的推断。标题自带编号（如“165.该章节未审核
 * 通过”）时原样保留；推断不出编号（跳号 / 首尾缺口）时保持原样，绝不拿会漂移的 TOC 序号顶上。
 * 用户确认（2026-10-04）：占位章要显示“184.该章节未审核通过”。
 */
internal fun pendingChapterHeading(chapter: OrderedOnlineChapter): String {
    val original = chapter.fallbackTitle.trim()
    if (original.isEmpty()) return "审核未通过"
    if (titleChapterNumber(original) != null) return original
    val number = chapter.displayNumber ?: return original
    return "$number.$original"
}

/**
 * 未审核章在成品里的正文：官方对未审核章节返回的就是这条占位文本
 * （与 CIWEIMAO_UNREVIEWED_MARKERS 中的“本章节内容未审核通过”一致）。
 * 写进成品时前面缩进两个全角空格，和书里其他正文段落一样——用户要的就是
 * “读到的内容出现在成品里”，而不是整章消失。
 */
internal fun pendingChapterNotice(): String = "　　本章节内容未审核通过"
