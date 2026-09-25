package com.avalon.cwm.backend.online

import com.avalon.cwm.backend.core.Book
import com.avalon.cwm.backend.core.ChapterAccess
import com.avalon.cwm.backend.core.FileTools
import com.avalon.cwm.backend.core.JsonFiles
import com.avalon.cwm.backend.core.RuntimePaths
import com.avalon.cwm.backend.export.EpubWriter
import com.avalon.cwm.backend.net.WebContentClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
 * "138-152" for the selected chapters, or null when nothing is selected.
 *
 * Prefers the printed title numbers; falls back to the positional index only
 * when every selected title is unnumbered, so a mixed list can never yield a
 * meaningless range.
 */
internal fun excerptSpan(selected: List<OrderedOnlineChapter>): String? {
    if (selected.isEmpty()) return null
    val titled = selected.map { titleChapterNumber(it.fallbackTitle) }
    val numbers = if (titled.all { it != null }) titled.filterNotNull()
        else selected.map { it.sequence }
    val first = numbers.minOrNull() ?: return null
    val last = numbers.maxOrNull() ?: first
    return if (first == last) "$first" else "$first-$last"
}

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
        val info: JSONObject = client.getBookInfo(request.bookId)
        val allChapters: List<OrderedOnlineChapter> = orderedChapters(client, request.bookId)
        val name = info.optString("book_name", "Book_${request.bookId}")
        val author = info.optString("author_name", "未知作者")
        val safeName = FileTools.sanitizeName(name).ifBlank { "Book_${request.bookId}" }
        var selected = selectChapters(allChapters, request)
        val outputSafeName = outputSafeName(safeName, request, selected)

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

        val selectedAccountId = request.accountId
        val selectedAccountLabel = ""
        val purchaseCost = java.math.BigDecimal.ZERO
        val purchaseReport: JSONArray? = null
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

        val cachedSelected = selected.count { it.chapterId in cachedIds }
        if (cachedSelected > 0) {
            onProgress(
                Progress(
                    0,
                    selected.size.coerceAtLeast(1),
                    "断点续传：已有 $cachedSelected 章缓存，只补剩余",
                ),
            )
        }
        var completed = 0
        val results = linkedMapOf<String, ChapterFetchResult>()
        fun onChapterDone(result: ChapterFetchResult) {
            if (result.status == "ok" && !result.content.isNullOrEmpty()) {
                saveChapterCache(cacheDirectory, result)
            }
            results[result.chapter.chapterId] = result
            completed += 1
            onProgress(
                Progress(
                    completed,
                    selected.size.coerceAtLeast(1),
                    progressMessage(result),
                ),
            )
        }

        if (request.accountStrategy == "single") {
            results.putAll(
                chapterDownloader.download(
                    client = client,
                    chapters = selected,
                    retries = ONLINE_DOWNLOAD_RETRY_LIMIT,
                    skipIds = cachedIds,
                    onDone = ::onChapterDone,
                ),
            )
        } else {
            // Cached chapters are account-independent local results. Every
            // uncached chapter is fetched through the account group that owns
            // its entitlement; no chapter is silently fetched with another
            // account's client.
            selected.filter { it.chapterId in cachedIds }.forEach { chapter ->
                onChapterDone(
                    ChapterFetchResult(
                        chapter = chapter,
                        title = null,
                        content = null,
                        status = "skip",
                    ),
                )
            }
            // Review-pending chapters are never fetched with any account; they
            // are reported as 未审核 so they are not counted as failures.
            selected.filter { it.pending && it.chapterId !in cachedIds }.forEach { chapter ->
                onChapterDone(
                    ChapterFetchResult(
                        chapter = chapter,
                        title = chapter.fallbackTitle,
                        content = null,
                        status = "pending",
                    ),
                )
            }
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
            resumed = cachedSelected,
            accountId = selectedAccountId,
            accountLabel = selectedAccountLabel,
            accountStrategy = request.accountStrategy,
            purchaseCost = purchaseCost,
            purchaseReport = purchaseReport,
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
                    pending = isUnreviewedChapterMeta(
                        chapter.opt("chapter_title"),
                        chapter.opt("is_valid"),
                    ),
                )
            }
        }
        return output.filter { it.chapterId.isNotEmpty() }
    }

    private fun selectChapters(
        chapters: List<OrderedOnlineChapter>,
        request: OnlineDownloadRequest,
    ): List<OrderedOnlineChapter> {
        val chapterIds = request.selection?.chapterIds.orEmpty()
        if (chapterIds.isNotEmpty()) {
            val selected = chapters.filter { it.chapterId in chapterIds }
            if (selected.isEmpty()) throw CiweimaoException("指定的章节 ID 不存在")
            return selected
        }
        val indexes = request.selection?.orderedIndexes ?: return chapters
        val selected = chapters.filter { it.sequence in indexes }
        if (selected.isEmpty()) {
            throw CiweimaoException("指定的章节序号超出范围（本书共 ${chapters.size} 章）")
        }
        return selected
    }

    private fun outputSafeName(
        base: String,
        request: OnlineDownloadRequest,
        selected: List<OrderedOnlineChapter>,
    ): String {
        val indexes = request.selection?.orderedIndexes
        if (!indexes.isNullOrEmpty()) {
            val first = indexes.minOrNull() ?: return base
            val last = indexes.maxOrNull() ?: first
            val span = if (first == last) "$first" else "$first-$last"
            return "${base}_节选$span"
        }
        val ids = request.selection?.chapterIds
        if (!ids.isNullOrEmpty()) {
            // The 指定章节 modal selects by chapter id; name the partial output
            // after the numbers printed in the chapter titles, because that is
            // what the reader picked in the picker. The TOC sequence is only a
            // positional index and drifts from the printed numbering whenever a
            // book carries front matter or skips a number, which is how a
            // 138-152 selection ended up written as "节选141-155".
            val span = excerptSpan(selected) ?: return base
            return "${base}_节选$span"
        }
        return base
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
            "pending" -> "第${result.chapter.sequence}章 该章节审核未通过"
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
        accountId: String,
        accountLabel: String,
        accountStrategy: String,
        purchaseCost: java.math.BigDecimal?,
        purchaseReport: JSONArray?,
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
            ordered.forEach { orderedChapter ->
                val loaded = loadChapter(orderedChapter, fetched, cacheDirectory)
                if (loaded == null) {
                    when (fetched[orderedChapter.chapterId]?.status ?: "error") {
                        "locked" -> locked += 1
                        "pending" -> {
                            pending += 1
                            pendingChapters += PendingChapter(orderedChapter.sequence, orderedChapter.fallbackTitle)
                        }
                        else -> failed += 1
                    }
                    return@forEach
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
            val reviewHint = if (pending > 0) "（其中 $pending 章未通过审核）" else ""
            throw CiweimaoException("未下载到任何有权限的章节$reviewHint")
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
            accountId = accountId,
            accountLabel = accountLabel,
            accountStrategy = accountStrategy,
            purchaseCost = purchaseCost,
            purchaseReport = purchaseReport,
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
