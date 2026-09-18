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
    val orderedIndexes: Set<Int>,
) {
    companion object {
        fun parse(raw: Any?): ChapterSelection? {
            val text = raw?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return null
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
            return indexes.takeIf { it.isNotEmpty() }?.let(::ChapterSelection)
        }
    }
}
