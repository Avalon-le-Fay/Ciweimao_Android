package com.avalon.cwm.backend.online

import com.avalon.cwm.backend.core.Book
import com.avalon.cwm.backend.core.ChapterAccess
import com.avalon.cwm.backend.core.FileTools
import com.avalon.cwm.backend.core.JsonFiles
import com.avalon.cwm.backend.core.RuntimePaths
import com.avalon.cwm.backend.export.EpubWriter
import com.avalon.cwm.backend.net.WebContentClient
import org.json.JSONObject
import java.io.File

class OnlineBookDownloader(
    private val paths: RuntimePaths,
    private val accounts: OnlineAccountRepository,
    private val web: WebContentClient,
    private val epubWriter: EpubWriter,
    private val chapterDownloader: ChapterBatchDownloader = ChapterBatchDownloader(),
) {
    data class Progress(
        val done: Int,
        val total: Int,
        val message: String,
    )

    fun download(
        request: OnlineDownloadRequest,
        onProgress: (Progress) -> Unit,
    ): OnlineBookResult {
        val client = accounts.client() ?: throw CiweimaoException("还未登录在线账号")
        val preset = ONLINE_DOWNLOAD_PRESETS[request.speed]
            ?: requireNotNull(ONLINE_DOWNLOAD_PRESETS["balance"])
        val info = client.getBookInfo(request.bookId)
        val name = info.optString("book_name", "Book_${request.bookId}")
        val author = info.optString("author_name", "未知作者")
        val safeName = FileTools.sanitizeName(name).ifBlank { "Book_${request.bookId}" }
        val allChapters = orderedChapters(client, request.bookId)
        val selected = selectChapters(allChapters, request)
        val outputSafeName = outputSafeName(safeName, request)

        val chapterOutput = File(paths.chapterOutput, outputSafeName)
        val cacheDirectory = File(paths.outputCache, request.bookId)
        val imageCache = File(paths.imageCache, outputSafeName)
        listOf(chapterOutput, cacheDirectory, imageCache).forEach(FileTools::ensureDirectory)
        JsonFiles.write(
            File(cacheDirectory, ".meta.json"),
            JSONObject()
                .put("book_id", request.bookId)
                .put("name", name)
                .put("safe", safeName),
        )

        val cachedIds = if (request.resume) {
            cacheDirectory.listFiles()?.filter { it.isFile && it.extension == "txt" }
                ?.mapTo(linkedSetOf()) { it.nameWithoutExtension }.orEmpty()
        } else {
            emptySet()
        }
        val book = Book(
            id = request.bookId.toLongOrNull() ?: 0L,
            name = name,
            author = author,
            coverUrl = info.optString("cover"),
        )
        book.cover = try {
            book.coverUrl.takeIf(String::isNotBlank)?.let { web.getBytes(it) } ?: byteArrayOf()
        } catch (_: Exception) {
            byteArrayOf()
        }

        if (cachedIds.isNotEmpty()) {
            onProgress(
                Progress(
                    0,
                    selected.size.coerceAtLeast(1),
                    "断点续传：已有 ${cachedIds.size} 章缓存，只补剩余",
                ),
            )
        }
        var completed = 0
        val results = chapterDownloader.download(
            client = client,
            chapters = selected,
            workers = preset.workers,
            delayMillis = preset.delayMillis,
            skipIds = cachedIds,
        ) { result ->
            if (result.status == "ok" && !result.content.isNullOrEmpty()) {
                saveChapterCache(cacheDirectory, result)
            }
            completed += 1
            onProgress(
                Progress(
                    completed,
                    selected.size.coerceAtLeast(1),
                    progressMessage(result),
                ),
            )
        }
        return assembleOutputs(
            book = book,
            request = request,
            outputSafeName = outputSafeName,
            ordered = selected,
            fetched = results,
            cacheDirectory = cacheDirectory,
            chapterOutput = chapterOutput,
            imageCache = imageCache,
            resumed = cachedIds.size,
        )
    }

    private fun orderedChapters(
        client: CiweimaoClient,
        bookId: String,
    ): List<OrderedOnlineChapter> {
        val toc = client.getToc(bookId)
        val output = mutableListOf<OrderedOnlineChapter>()
        var sequence = 0
        for (divisionIndex in 0 until toc.length()) {
            val division = toc.optJSONObject(divisionIndex) ?: continue
            val divisionName = division.optString("division_name")
            val chapters = division.optJSONArray("chapters") ?: continue
            for (chapterIndex in 0 until chapters.length()) {
                val chapter = chapters.optJSONObject(chapterIndex) ?: continue
                sequence += 1
                output += OrderedOnlineChapter(
                    sequence = sequence,
                    divisionName = divisionName,
                    chapterId = chapter.opt("chapter_id")?.toString().orEmpty(),
                    fallbackTitle = chapter.optString("chapter_title"),
                    access = when (chapter.optString("access")) {
                        ChapterAccess.OWNED.wireValue -> ChapterAccess.OWNED
                        ChapterAccess.LOCKED.wireValue -> ChapterAccess.LOCKED
                        else -> ChapterAccess.FREE
                    },
                )
            }
        }
        return output.filter { it.chapterId.isNotEmpty() }
    }

    private fun selectChapters(
        chapters: List<OrderedOnlineChapter>,
        request: OnlineDownloadRequest,
    ): List<OrderedOnlineChapter> {
        val indexes = request.selection?.orderedIndexes ?: return chapters
        val selected = chapters.filter { it.sequence in indexes }
        if (selected.isEmpty()) {
            throw CiweimaoException("指定的章节序号超出范围（本书共 ${chapters.size} 章）")
        }
        return selected
    }

    private fun outputSafeName(base: String, request: OnlineDownloadRequest): String {
        val indexes = request.selection?.orderedIndexes ?: return base
        val first = indexes.minOrNull() ?: return base
        val last = indexes.maxOrNull() ?: first
        val span = if (first == last) "$first" else "$first-$last"
        return "${base}_节选$span"
    }

    private fun saveChapterCache(directory: File, result: ChapterFetchResult) {
        val id = result.chapter.chapterId
        File(directory, "$id.txt").writeText(result.content.orEmpty(), Charsets.UTF_8)
        File(directory, "$id.title").writeText(
            result.title?.ifBlank { result.chapter.fallbackTitle }
                ?: result.chapter.fallbackTitle,
            Charsets.UTF_8,
        )
    }

    private fun progressMessage(result: ChapterFetchResult): String {
        val label = result.title?.ifBlank { result.chapter.fallbackTitle }
            ?: result.chapter.fallbackTitle
        return when (result.status) {
            "ok" -> "下载: $label"
            "skip" -> "已有缓存，跳过: $label"
            "locked" -> "未购买，跳过: $label"
            "pending" -> "未审核，跳过: $label"
            else -> "失败: $label"
        }
    }

    private fun assembleOutputs(
        book: Book,
        request: OnlineDownloadRequest,
        outputSafeName: String,
        ordered: List<OrderedOnlineChapter>,
        fetched: Map<String, ChapterFetchResult>,
        cacheDirectory: File,
        chapterOutput: File,
        imageCache: File,
        resumed: Int,
    ): OnlineBookResult {
        val completeText = File(paths.output, "$outputSafeName.txt")
        if (completeText.exists()) check(completeText.delete()) { "无法覆盖旧 TXT 成品" }

        var got = 0
        var locked = 0
        var failed = 0
        var pending = 0
        val pendingChapters = mutableListOf<PendingChapter>()
        val divisions = mutableListOf<com.avalon.cwm.backend.core.Division>()
        var currentDivision: com.avalon.cwm.backend.core.Division? = null
        var currentDivisionName: String? = null

        completeText.bufferedWriter(Charsets.UTF_8).use { writer ->
            ordered.forEachIndexed { index, orderedChapter ->
                val loaded = loadChapter(orderedChapter, fetched, cacheDirectory)
                if (loaded == null) {
                    when (fetched[orderedChapter.chapterId]?.status ?: "error") {
                        "locked" -> locked += 1
                        "pending" -> {
                            pending += 1
                            pendingChapters += PendingChapter(index + 1, orderedChapter.fallbackTitle)
                        }
                        else -> failed += 1
                    }
                    return@forEachIndexed
                }

                val (title, content) = loaded
                if (currentDivisionName != orderedChapter.divisionName) {
                    currentDivision = com.avalon.cwm.backend.core.Division(
                        id = divisions.size.toLong() + 1,
                        title = orderedChapter.divisionName,
                    )
                    divisions += requireNotNull(currentDivision)
                    currentDivisionName = orderedChapter.divisionName
                }
                writer.append(title).append('\n').append(content).append("\n\n")
                val safeTitle = FileTools.sanitizeName(title)
                    .ifBlank { orderedChapter.chapterId }
                File(chapterOutput, "$safeTitle.txt").writeText(content, Charsets.UTF_8)
                currentDivision?.chapters?.add(
                    com.avalon.cwm.backend.core.Chapter(
                        id = orderedChapter.chapterId.toLongOrNull() ?: 0L,
                        title = title,
                        content = content,
                    ),
                )
                got += 1
            }
        }

        if (got == 0) {
            completeText.delete()
            throw CiweimaoException("未下载到任何有权限的章节")
        }
        book.divisions.clear()
        book.divisions += divisions
        epubWriter.write(book, File(paths.output, "$outputSafeName.epub"), imageCache)
        return OnlineBookResult(
            name = book.name,
            got = got,
            locked = locked,
            failed = failed,
            pending = pending,
            pendingChapters = pendingChapters,
            resumed = resumed,
        )
    }

    private fun loadChapter(
        chapter: OrderedOnlineChapter,
        fetched: Map<String, ChapterFetchResult>,
        cacheDirectory: File,
    ): Pair<String, String>? {
        val fresh = fetched[chapter.chapterId]
        if (!fresh?.content.isNullOrEmpty()) {
            val title = fresh?.title?.ifBlank { chapter.fallbackTitle }
                ?: chapter.fallbackTitle
            return title to requireNotNull(fresh?.content)
        }

        val contentFile = File(cacheDirectory, "${chapter.chapterId}.txt")
        if (!contentFile.isFile) return null
        val content = runCatching { contentFile.readText(Charsets.UTF_8) }.getOrNull()
            ?.takeIf(String::isNotEmpty) ?: return null
        val titleFile = File(cacheDirectory, "${chapter.chapterId}.title")
        val title = runCatching { titleFile.readText(Charsets.UTF_8) }
            .getOrDefault(chapter.fallbackTitle)
            .ifBlank { chapter.fallbackTitle }
        return title to content
    }
}
