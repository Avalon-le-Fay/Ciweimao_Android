package com.avalon.cwm.backend.local

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.avalon.cwm.backend.core.Book
import com.avalon.cwm.backend.core.Chapter
import com.avalon.cwm.backend.core.Division
import com.avalon.cwm.backend.core.LibraryBookSummary
import org.json.JSONObject
import java.io.Closeable
import java.io.File

data class DatabaseCacheDeleteResult(
    val chapterIds: Set<String>,
    val rowsDeleted: Int,
    val rowsByTable: Map<String, Int>,
)

class CiweimaoDatabase private constructor(
    private val database: SQLiteDatabase,
) : Closeable {
    companion object {
        fun openReadOnly(file: File): CiweimaoDatabase {
            require(file.isFile) { "数据库不存在：${file.absolutePath}" }
            return CiweimaoDatabase(
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ),
            )
        }

        fun openReadWrite(file: File): CiweimaoDatabase {
            require(file.isFile) { "数据库不存在：${file.absolutePath}" }
            return CiweimaoDatabase(
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ),
            )
        }
    }

    fun getBookInfo(bookId: Long): JSONObject {
        for (table in listOf("shelf_book_info", "book_info")) {
            val raw = queryFirstString(
                "SELECT book_info FROM $table WHERE book_id = ? LIMIT 1",
                arrayOf(bookId.toString()),
            ) ?: continue
            try {
                return JSONObject(raw)
            } catch (_: Exception) {
            }
        }
        return JSONObject()
            .put("book_name", "未知书籍($bookId)")
            .put("author_name", "未知")
    }

    fun getBook(bookId: Long): Book {
        val info = getBookInfo(bookId)
        val book = Book(
            id = bookId,
            name = info.optString("book_name", "Book_$bookId"),
            author = info.optString("author_name", "未知作者"),
            coverUrl = info.optString("cover", ""),
        )
        getDivisions(bookId).forEach { division ->
            division.chapters += getChapters(bookId, division.id)
            book.divisions += division
        }
        return book
    }

    fun getDivisions(bookId: Long): List<Division> {
        val output = mutableListOf<Pair<Int, Division>>()
        querySafely(
            """
                SELECT division_id, division_index, max_chapter_index, division_name
                FROM division
                WHERE book_id = ?
            """.trimIndent(),
            arrayOf(bookId.toString()),
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                output += cursor.int("division_index") to Division(
                    id = cursor.long("division_id"),
                    title = cursor.string("division_name"),
                    maxChapterIndex = cursor.int("max_chapter_index"),
                )
            }
        }
        return output.sortedBy { it.first }.map { it.second }
    }

    fun getChapters(bookId: Long, divisionId: Long): List<Chapter> {
        val output = mutableListOf<Pair<Int, Chapter>>()
        querySafely(
            """
                SELECT chapter_id, chapter_title, chapter_index, auth_access, is_download
                FROM catalog1
                WHERE book_id = ? AND division_id = ?
            """.trimIndent(),
            arrayOf(bookId.toString(), divisionId.toString()),
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                output += cursor.int("chapter_index") to Chapter(
                    id = cursor.long("chapter_id"),
                    title = cursor.string("chapter_title"),
                    authAccess = cursor.int("auth_access") != 0,
                    downloaded = cursor.int("is_download") != 0,
                )
            }
        }
        return output.sortedBy { it.first }.map { it.second }
    }

    fun scanLibrary(): List<LibraryBookSummary> {
        val output = mutableListOf<LibraryBookSummary>()
        val seen = mutableSetOf<String>()
        for (table in listOf("shelf_book_info", "book_info")) {
            querySafely("SELECT book_id, book_info FROM $table", emptyArray())?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    if (!seen.add(id)) continue
                    val info = try {
                        JSONObject(cursor.getString(1) ?: "{}")
                    } catch (_: Exception) {
                        continue
                    }
                    output += LibraryBookSummary(
                        id = id,
                        name = info.optString("book_name", "书籍($id)"),
                        author = info.optString("author_name", "未知"),
                        cover = info.optString("cover", ""),
                    )
                }
            }
            if (output.isNotEmpty()) break
        }
        return output
    }

    fun chapterIdsForBooks(bookIds: Set<String>): Set<String> {
        val output = linkedSetOf<String>()
        listOf("catalog1", "chapter_info").forEach { table ->
            if (!tableHasColumns(table, "book_id", "chapter_id")) return@forEach
            bookIds.forEach { bookId ->
                querySafely(
                    "SELECT chapter_id FROM $table WHERE book_id = ?",
                    arrayOf(bookId),
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (!cursor.isNull(0)) {
                            cursor.getString(0)?.trim()?.takeIf(String::isNotEmpty)?.let(output::add)
                        }
                    }
                }
            }
        }
        return output
    }

    /** Deletes cache/index rows only. Shelf, bookmarks and reading history stay untouched. */
    fun deleteCacheForBooks(bookIds: Set<String>): DatabaseCacheDeleteResult {
        require(bookIds.isNotEmpty()) { "未提供书籍 ID" }
        check(tableHasColumns("catalog1", "book_id", "chapter_id")) {
            "数据库缺少 catalog1 缓存表或必要字段"
        }
        val chapterIds = chapterIdsForBooks(bookIds)
        val rowsByTable = linkedMapOf<String, Int>()
        val cacheTables = listOf("book_info", "cacheBook", "catalog1", "chapter_info", "division")

        database.beginTransaction()
        try {
            cacheTables.forEach { table ->
                if (!tableHasColumns(table, "book_id")) return@forEach
                var deleted = 0
                bookIds.forEach { bookId ->
                    deleted += database.delete(table, "book_id = ?", arrayOf(bookId))
                }
                if (deleted > 0) rowsByTable[table] = deleted
            }
            database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "数据库完整性检查失败"
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        database.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            while (cursor.moveToNext()) Unit
        }
        return DatabaseCacheDeleteResult(
            chapterIds = chapterIds,
            rowsDeleted = rowsByTable.values.sum(),
            rowsByTable = rowsByTable,
        )
    }

    private fun tableHasColumns(table: String, vararg required: String): Boolean {
        val columns = mutableSetOf<String>()
        querySafely("PRAGMA table_info($table)", emptyArray())?.use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (nameIndex >= 0 && cursor.moveToNext()) {
                cursor.getString(nameIndex)?.let(columns::add)
            }
        }
        return required.all(columns::contains)
    }

    private fun queryFirstString(sql: String, arguments: Array<String>): String? =
        querySafely(sql, arguments)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun querySafely(sql: String, arguments: Array<String>): Cursor? = try {
        database.rawQuery(sql, arguments)
    } catch (_: Exception) {
        null
    }

    override fun close() {
        database.close()
    }
}

private fun Cursor.index(name: String): Int = getColumnIndex(name).also {
    require(it >= 0) { "数据库缺少字段：$name" }
}

private fun Cursor.string(name: String): String =
    index(name).let { if (isNull(it)) "" else getString(it).orEmpty() }

private fun Cursor.int(name: String): Int =
    index(name).let { if (isNull(it)) 0 else getInt(it) }

private fun Cursor.long(name: String): Long =
    index(name).let { if (isNull(it)) 0L else getLong(it) }
