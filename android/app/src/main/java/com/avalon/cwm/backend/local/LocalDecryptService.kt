package com.avalon.cwm.backend.local

import android.util.Base64
import com.avalon.cwm.backend.core.Book
import com.avalon.cwm.backend.core.Chapter
import com.avalon.cwm.backend.core.CiweimaoCrypto
import com.avalon.cwm.backend.core.Division
import com.avalon.cwm.backend.core.FileTools
import com.avalon.cwm.backend.core.RuntimePaths
import com.avalon.cwm.backend.export.EpubWriter
import com.avalon.cwm.backend.net.WebContentClient
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors

class LocalDecryptService(
    private val paths: RuntimePaths,
    private val web: WebContentClient,
    private val epubWriter: EpubWriter,
    private val onOutputsChanged: () -> Unit,
) {
    private data class Task(
        var status: String,
        var percent: Int,
        var message: String,
        var name: String? = null,
        var error: String? = null,
    )

    private val lock = Any()
    private val queue = ArrayDeque<String>()
    private val tasks = linkedMapOf<String, Task>()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cwm-local-decrypt")
    }
    private var running = false
    private var current: String? = null

    fun enqueue(rawIds: Iterable<Any?>): Int {
        val ids = rawIds.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        require(ids.isNotEmpty()) { "未提供书籍 ID" }
        var added = 0
        var startWorker = false
        synchronized(lock) {
            ids.forEach { id ->
                if (id !in queue && !(current == id && tasks[id]?.status == "running")) {
                    queue.addLast(id)
                    tasks[id] = Task("queued", 0, "排队中...")
                    added += 1
                }
            }
            if (!running && queue.isNotEmpty()) {
                running = true
                startWorker = true
            }
        }
        if (startWorker) worker.execute(::drainQueue)
        return added
    }

    fun snapshot(): JSONObject = synchronized(lock) {
        val items = JSONObject()
        tasks.forEach { (id, task) -> items.put(id, task.toJson()) }
        JSONObject()
            .put("running", running)
            .put("current", current ?: JSONObject.NULL)
            .put("items", items)
    }

    fun isRunning(): Boolean = synchronized(lock) { running }

    fun <T> runWhileIdle(rawIds: Iterable<String>, action: () -> T): T = synchronized(lock) {
        check(!running && current == null && queue.isEmpty()) {
            "解密任务正在运行，请完成后再删除本地缓存"
        }
        val ids = rawIds.map(String::trim).filter(String::isNotEmpty).toSet()
        val result = action()
        ids.forEach { tasks.remove(it) }
        result
    }

    private fun drainQueue() {
        while (true) {
            val id = synchronized(lock) {
                if (queue.isEmpty()) {
                    running = false
                    current = null
                    return
                }
                queue.removeFirst().also { next ->
                    current = next
                    tasks[next] = Task("running", 0, "开始...")
                }
            }
            try {
                val result = decryptBook(id) { done, total, message ->
                    synchronized(lock) {
                        tasks[id]?.apply {
                            percent = if (total <= 0) 0 else done * 100 / total
                            this.message = message
                        }
                    }
                }
                synchronized(lock) {
                    tasks[id]?.apply {
                        status = "done"
                        percent = 100
                        name = result.first
                        message = "完成，导出 ${result.second} 章"
                    }
                }
                onOutputsChanged()
            } catch (error: Throwable) {
                val detail = error.message?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: error.javaClass.simpleName
                synchronized(lock) {
                    tasks[id]?.apply {
                        status = "error"
                        this.error = detail
                        message = "失败: $detail"
                    }
                }
            }
        }
    }

    private fun decryptBook(
        rawBookId: String,
        progress: (done: Int, total: Int, message: String) -> Unit,
    ): Pair<String, Int> {
        val bookId = rawBookId.toLongOrNull()
            ?: throw IllegalArgumentException("无效的书籍 ID: $rawBookId")
        val book = CiweimaoDatabase.openReadOnly(paths.database).use { database ->
            database.getBook(bookId)
        }
        require(book.divisions.isNotEmpty()) { "数据库中未找到分卷信息" }

        book.cover = try {
            book.coverUrl.takeIf(String::isNotBlank)?.let { web.getBytes(it) } ?: byteArrayOf()
        } catch (_: Exception) {
            byteArrayOf()
        }
        book.description = web.getBookDescription(bookId).ifBlank { "简介获取失败" }

        val outputText = File(paths.output, "${book.safeName}.txt")
        if (outputText.exists()) check(outputText.delete()) { "无法覆盖旧 TXT 成品" }
        val chapterOutput = File(paths.chapterOutput, book.safeName)
        val textCache = File(paths.textCache, book.safeName)
        val imageCache = File(paths.imageCache, book.safeName)
        listOf(chapterOutput, textCache, imageCache).forEach(FileTools::ensureDirectory)

        val encryptedBook = File(paths.data, bookId.toString())
        val total = book.divisions.sumOf { it.chapters.size }.coerceAtLeast(1)
        var done = 0
        var processed = 0
        val validDivisions = mutableListOf<Division>()

        outputText.bufferedWriter(Charsets.UTF_8).use { completeBook ->
            book.divisions.forEach { division ->
                val validChapters = mutableListOf<Chapter>()
                division.chapters.forEach chapterLoop@ { chapter ->
                    done += 1
                    progress(done, total, "解密: ${chapter.title}")
                    val chapterId = chapter.id.toString()
                    val encrypted = findChapterFile(encryptedBook, chapterId)
                    val key = findKeyFile(paths.keys, chapterId)
                    val decrypted = File(textCache, "$chapterId.txt")
                    chapter.encryptedFile = encrypted
                    chapter.keyFile = key
                    chapter.decryptedFile = decrypted

                    chapter.content = when {
                        decrypted.isFile -> runCatching { decrypted.readText(Charsets.UTF_8) }.getOrDefault("")
                        encrypted != null && key != null -> runCatching {
                            val text = CiweimaoCrypto.decryptChapter(
                                encrypted.readText(Charsets.UTF_8),
                                key.readText(Charsets.UTF_8),
                            )
                            decrypted.writeText(text, Charsets.UTF_8)
                            text
                        }.getOrDefault("")
                        else -> ""
                    }
                    if (chapter.content.isEmpty()) return@chapterLoop

                    completeBook.append(chapter.title).append('\n')
                        .append(chapter.content).append("\n\n")
                    val safeTitle = FileTools.sanitizeName(chapter.title).ifBlank { chapterId }
                    File(chapterOutput, "$safeTitle.txt").writeText(chapter.content, Charsets.UTF_8)
                    validChapters += chapter
                    processed += 1
                }
                if (validChapters.isNotEmpty()) {
                    validDivisions += division.copy(chapters = validChapters)
                }
            }
        }

        if (processed == 0) {
            outputText.delete()
            throw IllegalStateException("解密数量为 0，请检查本地缓存文件是否完整")
        }
        book.divisions.clear()
        book.divisions += validDivisions
        progress(total, total, "正在生成 EPUB...")
        epubWriter.write(book, File(paths.output, "${book.safeName}.epub"), imageCache)
        return book.name to processed
    }

    private fun findChapterFile(folder: File, chapterId: String): File? =
        folder.listFiles()?.firstOrNull { file ->
            file.isFile && (
                file.name == chapterId ||
                    file.name == "$chapterId.txt" ||
                    file.name.startsWith(chapterId)
                )
        }

    private fun findKeyFile(folder: File, chapterId: String): File? {
        val prefix = Base64.encodeToString(chapterId.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            .trimEnd('=')
        return folder.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(prefix) }
    }

    private fun Task.toJson(): JSONObject = JSONObject()
        .put("status", status)
        .put("percent", percent)
        .put("message", message)
        .put("name", name ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL)
}
