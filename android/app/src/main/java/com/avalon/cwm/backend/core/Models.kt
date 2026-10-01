package com.avalon.cwm.backend.core

import java.io.File

data class Chapter(
    val id: Long,
    val title: String,
    val authAccess: Boolean = false,
    val downloaded: Boolean = false,
    var content: String = "",
    var encryptedFile: File? = null,
    var keyFile: File? = null,
    var decryptedFile: File? = null,
)

data class Division(
    val id: Long,
    val title: String,
    val maxChapterIndex: Int = 0,
    val chapters: MutableList<Chapter> = mutableListOf(),
)

data class Book(
    val id: Long,
    var name: String = "未命名",
    var author: String = "佚名",
    var coverUrl: String = "",
    var cover: ByteArray = byteArrayOf(),
    var description: String = "",
    val divisions: MutableList<Division> = mutableListOf(),
) {
    val safeName: String
        get() = FileTools.sanitizeName(name).ifBlank { "Book_$id" }
}

data class LocalBookSummary(
    val id: String,
    val name: String,
    val author: String,
    val cover: String,
    val chaptersCached: Int,
    val unknown: Boolean,
)

data class LibraryBookSummary(
    val id: String,
    val name: String,
    val author: String,
    val cover: String,
)

data class OutputSummary(
    val name: String,
    val size: Long,
    val type: String,
)

enum class ChapterAccess(val wireValue: String) {
    FREE("free"),
    OWNED("owned"),
    LOCKED("locked");

    companion object {
        fun from(isPaid: Any?, authAccess: Any?): ChapterAccess {
            val paid = isPaid?.toString() == "1"
            val authorised = authAccess?.toString() == "1"
            return if (!paid) FREE else if (authorised) OWNED else LOCKED
        }
    }
}

data class ChapterSelection(
    val orderedIndexes: Set<Int> = emptySet(),
    val chapterIds: Set<String> = emptySet(),
) {
    companion object {
        fun parse(raw: Any?, ids: Any? = null): ChapterSelection? {
            val chapterIds = if (ids is org.json.JSONArray) (0 until ids.length()).mapNotNull { ids.optString(it).trim().ifBlank { null } }.toSet() else emptySet()
            val text = raw?.toString()?.trim().orEmpty()
            // A chapter-ids-only selection is complete on its own: the 指定章节
            // modal sends chapter_ids without a range string, and returning null
            // here made "download the checked chapters" fall back to the whole book.
            if (text.isEmpty() && chapterIds.isEmpty()) return null
            val indexes = linkedSetOf<Int>()
            text.replace('，', ',').replace('－', '-').split(',').forEach { rawPart ->
                val part = rawPart.trim()
                if (part.isEmpty()) return@forEach
                if ('-' in part) {
                    val pieces = part.split('-', limit = 2)
                    val first = pieces[0].trim().toIntOrNull()
                        ?: throw IllegalArgumentException("章节范围格式错误: $part")
                    val last = pieces[1].trim().toIntOrNull()
                        ?: throw IllegalArgumentException("章节范围格式错误: $part")
                    val range = if (first <= last) first..last else last..first
                    indexes.addAll(range)
                } else {
                    indexes += part.toIntOrNull()
                        ?: throw IllegalArgumentException("章节序号格式错误: $part")
                }
            }
            return if (indexes.isNotEmpty() || chapterIds.isNotEmpty()) ChapterSelection(indexes, chapterIds) else null
        }
    }
}
