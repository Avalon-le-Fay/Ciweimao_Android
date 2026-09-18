package com.avalon.cwm.backend.online

import com.avalon.cwm.backend.core.ChapterAccess

data class OrderedOnlineChapter(
    val sequence: Int,
    val divisionName: String,
    val chapterId: String,
    val fallbackTitle: String,
    val access: ChapterAccess,
)

data class ChapterFetchResult(
    val chapter: OrderedOnlineChapter,
    val title: String?,
    val content: String?,
    val status: String,
)

class ChapterBatchDownloader(
    private val batchSize: Int = 10,
) {
    @Suppress("UNUSED_PARAMETER")
    fun download(
        client: CiweimaoClient,
        chapters: List<OrderedOnlineChapter>,
        workers: Int,
        delayMillis: Long,
        retries: Int = 2,
        skipIds: Set<String> = emptySet(),
        onDone: (ChapterFetchResult) -> Unit,
    ): Map<String, ChapterFetchResult> {
        if (chapters.isEmpty()) return emptyMap()
        val results = linkedMapOf<String, ChapterFetchResult>()
        val pending = mutableListOf<OrderedOnlineChapter>()

        chapters.forEach { chapter ->
            val immediate = when {
                chapter.chapterId in skipIds -> ChapterFetchResult(
                    chapter = chapter,
                    title = null,
                    content = null,
                    status = "skip",
                )
                chapter.access == ChapterAccess.LOCKED -> ChapterFetchResult(
                    chapter = chapter,
                    title = chapter.fallbackTitle,
                    content = null,
                    status = "locked",
                )
                else -> null
            }
            if (immediate == null) {
                pending += chapter
            } else {
                results[chapter.chapterId] = immediate
                onDone(immediate)
            }
        }

        var allBatchesSucceeded = true
        pending.chunked(batchSize.coerceAtLeast(1)).forEach { batch ->
            var batchSucceeded = false
            val fetched = try {
                val ids = batch.map(OrderedOnlineChapter::chapterId)
                val command = client.getChapterDownloadCommand(ids)
                client.downloadChapterBatch(ids, command).also { batchSucceeded = true }
            } catch (error: CiweimaoAuthException) {
                throw error
            } catch (_: Throwable) {
                allBatchesSucceeded = false
                emptyMap()
            }
            batch.forEach { chapter ->
                val response = fetched[chapter.chapterId]
                val result = ChapterFetchResult(
                    chapter = chapter,
                    title = response?.title ?: chapter.fallbackTitle,
                    content = response?.content,
                    status = response?.status ?: if (batchSucceeded) "pending" else "error",
                )
                results[chapter.chapterId] = result
                onDone(result)
            }
        }

        if (pending.isNotEmpty() && allBatchesSucceeded) {
            client.completeChapterDownload()
        }
        return results
    }
}
