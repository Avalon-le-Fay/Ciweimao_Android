package com.avalon.cwm.backend.local

import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.util.Base64
import com.avalon.cwm.RootImportGateway
import com.avalon.cwm.backend.core.FileTools
import com.avalon.cwm.backend.core.JsonFiles
import com.avalon.cwm.backend.core.LocalBookSummary
import com.avalon.cwm.backend.core.OutputSummary
import com.avalon.cwm.backend.core.RuntimePaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class CacheSource(
    val mode: String,
    val databaseInput: String,
    val booksInput: String,
    val keysInput: String,
    val database: File?,
    val books: File?,
    val keys: File?,
    val missing: List<String>,
    val bookIds: List<String>,
    val keyCount: Int,
) {
    val ok: Boolean get() = missing.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode)
        .put("ok", ok)
        .put("path", if (mode == "combined") databaseInput else "")
        .put("exists", listOf(databaseInput, booksInput, keysInput).all { it.isNotEmpty() && File(it).exists() })
        .put("missing", JSONArray(missing))
        .put("keys", keyCount)
        .put("books", JSONArray(bookIds))
        .put("db", database?.absolutePath ?: JSONObject.NULL)
        .put("key_dir", keys?.absolutePath ?: JSONObject.NULL)
        .put("books_dir", books?.absolutePath ?: JSONObject.NULL)
        .put(
            "paths",
            JSONObject()
                .put("db_path", databaseInput)
                .put("books_path", booksInput)
                .put("keys_path", keysInput),
        )
        .put(
            "resolved",
            JSONObject()
                .put("database", database?.absolutePath ?: JSONObject.NULL)
                .put("books", books?.absolutePath ?: JSONObject.NULL)
                .put("keys", keys?.absolutePath ?: JSONObject.NULL),
        )
}

class CacheRepository(private val paths: RuntimePaths) {
    companion object {
        private val importLock = ReentrantLock()
    }

    private val combinedSourceFile = paths.stateFile(".cache_config")
    private val splitSourceFile = paths.stateFile(".cache_split_config")
    private val sourceModeFile = paths.stateFile(".cache_source_mode")
    private val favouriteSourcesFile = paths.stateFile(".import_favsrc")

    fun normalise(raw: Any?): String {
        val value = raw?.toString()?.trim().orEmpty()
        if (value == File.separator) return value
        return value.trimEnd('/', '\\')
    }

    fun probeCombined(rawPath: String): CacheSource {
        val path = normalise(rawPath)
        return probe(path, path, path, "combined")
    }

    fun probeSplit(databasePath: String, booksPath: String, keysPath: String): CacheSource =
        probe(normalise(databasePath), normalise(booksPath), normalise(keysPath), "split")

    fun importCombined(rawPath: String): JSONObject {
        val source = probeCombined(rawPath)
        val result = importResolved(source)
        JsonFiles.writeTextAtomic(combinedSourceFile, normalise(rawPath))
        JsonFiles.writeTextAtomic(sourceModeFile, "combined")
        return result
    }

    fun importSplit(
        databasePath: String,
        booksPath: String,
        keysPath: String,
        savedPaths: JSONObject? = null,
        sourceMode: String = "split",
    ): JSONObject {
        require(sourceMode == "split" || sourceMode == "root_split") {
            "无效的分散导入模式: $sourceMode"
        }
        val source = probeSplit(databasePath, booksPath, keysPath)
        val result = importResolved(source)
        JsonFiles.write(
            splitSourceFile,
            JSONObject()
                .put("db_path", normalise(savedPaths?.optString("db_path") ?: databasePath))
                .put("books_path", normalise(savedPaths?.optString("books_path") ?: booksPath))
                .put("keys_path", normalise(savedPaths?.optString("keys_path") ?: keysPath)),
        )
        JsonFiles.writeTextAtomic(sourceModeFile, sourceMode)
        return result
    }

    fun sourceMode(): String {
        val explicit = sourceModeFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (explicit == "combined" || explicit == "split" || explicit == "root_split") return explicit
        val candidates = mutableListOf<Pair<Long, String>>()
        if (savedCombined().isNotEmpty()) candidates += combinedSourceFile.lastModified() to "combined"
        val split = savedSplit()
        if (listOf("db_path", "books_path", "keys_path").all { split.optString(it).isNotEmpty() }) {
            candidates += splitSourceFile.lastModified() to "split"
        }
        return candidates.maxByOrNull { it.first }?.second.orEmpty()
    }

    fun savedCombined(): String = combinedSourceFile
        .takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim().orEmpty()

    fun clearSavedCombined() {
        JsonFiles.writeTextAtomic(combinedSourceFile, "")
    }

    fun savedSplit(): JSONObject = JsonFiles.readObject(splitSourceFile) ?: JSONObject()

    fun favouriteSources(): MutableList<String> = JsonFiles.stringList(favouriteSourcesFile)

    fun saveFavouriteSources(values: Iterable<String>): List<String> =
        JsonFiles.writeStringList(favouriteSourcesFile, values)

    fun scanLocalBooks(): List<LocalBookSummary> {
        if (!paths.data.isDirectory) return emptyList()
        val database = runCatching { CiweimaoDatabase.openReadOnly(paths.database) }.getOrNull()
        return try {
            paths.data.listFiles()?.filter { it.isDirectory && it.name.all(Char::isDigit) }
                ?.sortedBy { it.name.toLongOrNull() ?: Long.MAX_VALUE }
                ?.map { directory ->
                    val id = directory.name
                    val info = database?.getBookInfo(id.toLong()) ?: JSONObject()
                    val name = info.optString("book_name", "未知书籍($id)")
                    LocalBookSummary(
                        id = id,
                        name = name,
                        author = info.optString("author_name", "未知"),
                        cover = info.optString("cover", ""),
                        chaptersCached = directory.listFiles()?.count { it.isFile } ?: 0,
                        unknown = name.contains("未知书籍"),
                    )
                }.orEmpty()
        } finally {
            database?.close()
        }
    }

    fun scanLibrary() = runCatching {
        CiweimaoDatabase.openReadOnly(paths.database).use { it.scanLibrary() }
    }.getOrDefault(emptyList())

    fun listOutputs(): List<OutputSummary> = paths.output.listFiles()
        ?.filter { file ->
            file.isFile && file.extension.lowercase() in setOf("txt", "epub") &&
                !FileTools.isSymbolicLink(file)
        }
        ?.sortedBy { it.name }
        ?.map { OutputSummary(it.name, it.length(), it.extension.lowercase()) }
        .orEmpty()

    private data class DeleteStats(
        val encryptedChapters: Int,
        val keys: Int,
        val databaseRows: Int,
        val databaseChapters: Int,
        val rowsByTable: Map<String, Int>,
        val keyNames: List<String>,
        val stagedCleanupOk: Boolean,
    )

    fun deleteLocalBooks(rawIds: Iterable<Any?>): JSONObject {
        val ids = rawIds.mapNotNull { it?.toString()?.trim() }
            .filter(String::isNotEmpty)
            .distinct()
        require(ids.isNotEmpty()) { "未提供书籍 ID" }
        ids.forEach { id ->
            require(id.all(Char::isDigit) && id.length <= 32) { "无效的书籍 ID: $id" }
        }

        return importLock.withLock {
            val mode = sourceMode()
            val rootSource = mode == "root_split" ||
                (mode == "split" && savedSplitRequiresRoot())
            val source = if (rootSource) null else savedSourceForDelete()
            val rootSourceStats = if (rootSource) deleteRootSource(ids) else null
            val sourceDatabase = source?.database
            val sourceBooks = source?.books
            val sourceKeys = source?.keys
            val sameAsInternal = source != null &&
                sourceDatabase?.canonicalFile == paths.database.canonicalFile &&
                sourceBooks?.canonicalFile == paths.data.canonicalFile &&
                sourceKeys?.canonicalFile == paths.keys.canonicalFile
            val sourceStats = rootSourceStats ?: if (source == null) {
                null
            } else {
                deleteFromLocation(
                    databaseFile = requireNotNull(sourceDatabase),
                    booksRoot = requireNotNull(sourceBooks),
                    keysRoot = requireNotNull(sourceKeys),
                    ids = ids,
                    label = "source",
                )
            }
            val internalStats = if (sameAsInternal) {
                requireNotNull(sourceStats)
            } else {
                deleteFromLocation(
                    databaseFile = paths.database,
                    booksRoot = paths.data,
                    keysRoot = paths.keys,
                    ids = ids,
                    label = "internal",
                )
            }

            val tables = JSONObject()
            internalStats.rowsByTable.forEach { (table, count) -> tables.put(table, count) }
            JSONObject()
                .put("deleted", JSONArray(ids))
                .put("encrypted_chapters", internalStats.encryptedChapters)
                .put("keys", internalStats.keys)
                .put("database_chapters", internalStats.databaseChapters)
                .put("database_rows", internalStats.databaseRows)
                .put("database_tables", tables)
                .put("source_deleted", sourceStats != null)
                .put("source_mode", if (rootSourceStats != null) "root_split" else source?.mode ?: "none")
                .put("source_encrypted_chapters", sourceStats?.encryptedChapters ?: 0)
                .put("source_keys", sourceStats?.keys ?: 0)
                .put(
                    "staged_cleanup_ok",
                    sourceStats?.let { it.stagedCleanupOk && internalStats.stagedCleanupOk }
                        ?: internalStats.stagedCleanupOk,
                )
                .put("preserved_user_state", true)
        }
    }

    fun savedSplitRequiresRoot(): Boolean {
        val saved = savedSplit()
        val databasePath = saved.optString("db_path").trim()
        val booksPath = saved.optString("books_path").trim()
        val keysPath = saved.optString("keys_path").trim()
        if (listOf(databasePath, booksPath, keysPath).any(String::isBlank)) return false
        return !probeSplit(databasePath, booksPath, keysPath).ok
    }

    private fun savedSourceForDelete(): CacheSource? {
        val mode = sourceMode()
        val source = when (mode) {
            "combined" -> {
                val path = savedCombined()
                require(path.isNotBlank()) { "未保存导入来源，无法同时清理原地址" }
                probeCombined(path)
            }
            "split" -> {
                val saved = savedSplit()
                val databasePath = saved.optString("db_path").trim()
                val booksPath = saved.optString("books_path").trim()
                val keysPath = saved.optString("keys_path").trim()
                require(listOf(databasePath, booksPath, keysPath).all(String::isNotBlank)) {
                    "未保存完整导入来源，无法同时清理原地址"
                }
                probeSplit(databasePath, booksPath, keysPath)
            }
            "root_split" -> return null
            else -> return null
        }
        require(source.ok) { "原导入来源不可用，未执行删除: ${source.missing.joinToString("、")}" }
        require(source.database?.canWrite() == true) { "原来源数据库不可写，未执行删除" }
        require(source.books?.canWrite() == true) { "原来源章节目录不可写，未执行删除" }
        require(source.keys?.canWrite() == true) { "原来源密钥目录不可写，未执行删除" }
        return source
    }

    private fun deleteRootSource(ids: List<String>): DeleteStats {
        val saved = savedSplit()
        val databasePath = saved.optString("db_path").trim()
        val booksPath = saved.optString("books_path").trim()
        val keysPath = saved.optString("keys_path").trim()
        require(listOf(databasePath, booksPath, keysPath).all(String::isNotBlank)) {
            "未保存完整 Root 导入来源"
        }
        val staged = JSONObject(RootImportGateway.importForHttp(databasePath, booksPath, keysPath))
        check(staged.optBoolean("ok")) {
            staged.optString("error", "Root 来源读取失败")
        }
        val token = staged.optString("token")
        var committed = false
        try {
            val source = probeSplit(
                staged.optString("db_path"),
                staged.optString("books_path"),
                staged.optString("keys_path"),
            )
            check(source.ok) { "Root 暂存来源不完整: ${source.missing.joinToString("、")}" }
            val stats = deleteFromLocation(
                databaseFile = requireNotNull(source.database),
                booksRoot = requireNotNull(source.books),
                keysRoot = requireNotNull(source.keys),
                ids = ids,
                label = "root-source",
            )
            val result = JSONObject(
                RootImportGateway.commitDeleteForHttp(token, ids, stats.keyNames),
            )
            check(result.optBoolean("ok")) {
                result.optString("error", "Root 原来源回写失败")
            }
            committed = true
            return stats
        } finally {
            if (!committed && token.isNotBlank()) RootImportGateway.cleanup(token)
        }
    }

    private fun deleteFromLocation(
        databaseFile: File,
        booksRoot: File,
        keysRoot: File,
        ids: List<String>,
        label: String,
    ): DeleteStats {
        require(databaseFile.isFile && databaseFile.canWrite()) { "数据库不可写: ${databaseFile.absolutePath}" }
        val canonicalBooks = booksRoot.canonicalFile
        val canonicalKeys = keysRoot.canonicalFile
        val bookDirectories = ids.associateWith { id ->
            File(canonicalBooks, id).canonicalFile.also { directory ->
                require(directory.parentFile == canonicalBooks && directory.isDirectory) {
                    "本地缓存不存在: $id"
                }
            }
        }
        val cachedChapterIds = bookDirectories.values.flatMap { directory ->
            directory.listFiles()?.filter { it.isFile }?.mapNotNull { file ->
                file.name.takeWhile(Char::isDigit).takeIf(String::isNotEmpty)
            }.orEmpty()
        }.toSet()
        val databaseChapterIds = CiweimaoDatabase.openReadOnly(databaseFile).use { database ->
            database.chapterIdsForBooks(ids.toSet())
        }
        val chapterIds = databaseChapterIds + cachedChapterIds
        val keyFiles = canonicalKeys.listFiles()?.filter { file ->
            if (!file.isFile || FileTools.isSymbolicLink(file)) return@filter false
            val decoded = runCatching {
                String(Base64.decode(file.name, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("")
            chapterIds.any(decoded::startsWith)
        }.orEmpty().distinctBy { it.name }
        val encryptedChapterCount = bookDirectories.values.sumOf { directory ->
            directory.listFiles()?.count { it.isFile } ?: 0
        }

        val nonce = System.nanoTime()
        val stagedBooks = File(canonicalBooks, ".cwm-delete-$label-$nonce")
        val stagedKeys = File(canonicalKeys, ".cwm-delete-$label-$nonce")
        FileTools.ensureDirectory(stagedBooks)
        FileTools.ensureDirectory(stagedKeys)
        val moved = mutableListOf<Pair<File, File>>()
        fun stage(original: File, destination: File) {
            destination.parentFile?.let(FileTools::ensureDirectory)
            Os.rename(original.absolutePath, destination.absolutePath)
            moved += original to destination
        }

        var databaseCommitted = false
        try {
            bookDirectories.forEach { (id, directory) -> stage(directory, File(stagedBooks, id)) }
            keyFiles.forEach { key -> stage(key, File(stagedKeys, key.name)) }
            val databaseResult = CiweimaoDatabase.openReadWrite(databaseFile).use { database ->
                database.deleteCacheForBooks(ids.toSet())
            }
            databaseCommitted = true
            val booksCleanupOk = stagedBooks.deleteRecursively()
            val keysCleanupOk = stagedKeys.deleteRecursively()
            val cleanupOk = booksCleanupOk && keysCleanupOk
            return DeleteStats(
                encryptedChapters = encryptedChapterCount,
                keys = keyFiles.size,
                databaseRows = databaseResult.rowsDeleted,
                databaseChapters = databaseResult.chapterIds.size,
                rowsByTable = databaseResult.rowsByTable,
                keyNames = keyFiles.map { it.name },
                stagedCleanupOk = cleanupOk,
            )
        } catch (error: Throwable) {
            if (!databaseCommitted) {
                moved.asReversed().forEach { (original, staged) ->
                    if (staged.exists() && !original.exists()) {
                        runCatching {
                            original.parentFile?.let(FileTools::ensureDirectory)
                            Os.rename(staged.absolutePath, original.absolutePath)
                        }.exceptionOrNull()?.let(error::addSuppressed)
                    }
                }
                stagedBooks.deleteRecursively()
                stagedKeys.deleteRecursively()
            }
            throw error
        }
    }

    private fun probe(
        databasePath: String,
        booksPath: String,
        keysPath: String,
        mode: String,
    ): CacheSource {
        val database = resolveDatabase(databasePath)
        val books = resolveNamedDirectory(booksPath, listOf("booksnew"))
        val keys = resolveNamedDirectory(keysPath, listOf("Y2hlcy8", "Y2hlcy"))
        val missing = mutableListOf<String>()
        if (database == null) missing += pathProblem(databasePath, "数据库路径", "novelCiwei 或 novelCiwei.db")
        if (books == null) missing += pathProblem(booksPath, "章节路径", "booksnew 目录")
        if (keys == null) missing += pathProblem(keysPath, "密钥路径", "Y2hlcy8 或 Y2hlcy 目录")
        val bookIds = books?.listFiles()?.filter { it.isDirectory && it.name.all(Char::isDigit) }
            ?.map { it.name }?.sorted().orEmpty()
        val keyCount = keys?.listFiles()?.count { it.isFile } ?: 0
        return CacheSource(
            mode,
            databasePath,
            booksPath,
            keysPath,
            database,
            books,
            keys,
            missing,
            bookIds,
            keyCount,
        )
    }

    private fun resolveDatabase(raw: String): File? {
        if (raw.isEmpty()) return null
        val path = File(raw)
        if (path.isFile && path.canRead()) return path
        if (!path.isDirectory) return null
        return listOf("novelCiwei", "novelCiwei.db")
            .map { File(path, it) }.firstOrNull { it.isFile && it.canRead() }
    }

    private fun resolveNamedDirectory(raw: String, names: List<String>): File? {
        if (raw.isEmpty()) return null
        val path = File(raw)
        if (path.isDirectory && path.name in names && path.canRead()) return path
        if (!path.isDirectory) return null
        return names.map { File(path, it) }.firstOrNull { it.isDirectory && it.canRead() }
    }

    private fun pathProblem(raw: String, label: String, expected: String): String {
        if (raw.isEmpty()) return "未填写$label"
        val path = File(raw)
        return when {
            !path.exists() -> "${label}不存在: $raw"
            !path.canRead() -> "${label}无权读取: $raw"
            path.isDirectory -> "${label}中未找到${expected}: $raw"
            else -> "${label}类型不正确: $raw（需要${expected}）"
        }
    }

    private fun importResolved(source: CacheSource): JSONObject {
        require(source.ok) { "缓存来源不完整: ${source.missing.joinToString("、")}" }
        val database = requireNotNull(source.database)
        val books = requireNotNull(source.books)
        val keys = requireNotNull(source.keys)
        return importLock.withLock {
            snapshotDatabase(database, paths.database)
            var keyCount = 0
            keys.listFiles()?.filter { it.isFile }?.forEach { file ->
                FileTools.copyVerified(file, File(paths.keys, file.name))
                keyCount += 1
            }
            var bookCount = 0
            var chapterCount = 0
            books.listFiles()?.filter { it.isDirectory && it.name.all(Char::isDigit) }
                ?.forEach { bookDirectory ->
                    val destination = File(paths.data, bookDirectory.name)
                    FileTools.ensureDirectory(destination)
                    bookDirectory.listFiles()?.filter { it.isFile }?.forEach { chapter ->
                        FileTools.copyVerified(chapter, File(destination, chapter.name))
                        chapterCount += 1
                    }
                    bookCount += 1
                }
            JSONObject()
                .put("keys", keyCount)
                .put("books", bookCount)
                .put("chapters", chapterCount)
                .put("mode", source.mode)
                .put("resolved", source.toJson().getJSONObject("resolved"))
        }
    }

    private fun snapshotDatabase(source: File, destination: File) {
        val stageRoot = File(paths.root, ".database-import-${System.nanoTime()}")
        FileTools.ensureDirectory(stageRoot)
        val stageDatabase = File(stageRoot, "novelCiwei.db")
        try {
            FileTools.copyVerified(source, stageDatabase)
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val sidecar = File(source.absolutePath + suffix)
                if (sidecar.isFile) FileTools.copyVerified(sidecar, File(stageDatabase.absolutePath + suffix))
            }
            val opened = SQLiteDatabase.openDatabase(
                stageDatabase.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            try {
                opened.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                    while (cursor.moveToNext()) Unit
                }
            } finally {
                opened.close()
            }
            listOf("-wal", "-shm", "-journal").forEach { File(stageDatabase.absolutePath + it).delete() }
            destination.parentFile?.let(FileTools::ensureDirectory)
            val incoming = File(destination.parentFile, destination.name + ".importing")
            if (incoming.exists()) check(incoming.delete()) { "无法清理旧数据库导入文件" }
            FileTools.copyVerified(stageDatabase, incoming)
            listOf("-wal", "-shm", "-journal").forEach { File(destination.absolutePath + it).delete() }
            Os.rename(incoming.absolutePath, destination.absolutePath)
        } finally {
            stageRoot.deleteRecursively()
        }
    }
}
