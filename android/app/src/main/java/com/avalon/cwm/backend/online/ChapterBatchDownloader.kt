package com.avalon.cwm.backend.online

import com.avalon.cwm.backend.core.ChapterAccess
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class OrderedOnlineChapter(
    val sequence: Int,
    val divisionName: String,
    val chapterId: String,
    val fallbackTitle: String,
    val access: ChapterAccess,
    val unitHlb: BigDecimal? = null,
    val pending: Boolean = false,
)

data class ChapterFetchResult(
    val chapter: OrderedOnlineChapter,
    val title: String?,
    val content: String?,
    val status: String,
)

/**
 * Downloads chapters using the fixed-window behavior of the official BuyDownThread.
 * Each task is one ten-chapter batch. At most ten batches are in flight; a terminal
 * batch opens one slot for the next batch. Only transport failures are retried.
 */
class ChapterBatchDownloader(
    private val batchSize: Int = ONLINE_DOWNLOAD_BATCH_SIZE,
) {
    fun download(
        client: CiweimaoClient,
        chapters: List<OrderedOnlineChapter>,
        retries: Int = ONLINE_DOWNLOAD_RETRY_LIMIT,
        skipIds: Set<String> = emptySet(),
        onDone: (ChapterFetchResult) -> Unit,
    ): Map<String, ChapterFetchResult> {
        val result = downloadWithFetcher(
            chapters = chapters,
            retries = retries,
            skipIds = skipIds,
            onDone = onDone,
        ) { batch, retryLimit -> fetchBatch(client, batch, retryLimit) }
        val pendingIds = chapters.asSequence()
            .filter { it.chapterId !in skipIds && it.access != ChapterAccess.LOCKED && !it.pending }
            .map(OrderedOnlineChapter::chapterId)
            .toList()
        val allBatchesSucceeded = pendingIds.isNotEmpty() && pendingIds.all { id ->
            result[id]?.status != null && result[id]?.status != "error"
        }
        if (allBatchesSucceeded) client.completeChapterDownload()
        return result
    }

    /**
     * Scheduler seam for offline tests. The fetcher represents the complete
     * 285 -> 286 operation for one batch and may throw to model a failed request.
     */
    internal fun downloadWithFetcher(
        chapters: List<OrderedOnlineChapter>,
        retries: Int = ONLINE_DOWNLOAD_RETRY_LIMIT,
        skipIds: Set<String> = emptySet(),
        onDone: (ChapterFetchResult) -> Unit,
        fetcher: (List<OrderedOnlineChapter>, Int) -> Map<String, ChapterTextResult>?,
    ): Map<String, ChapterFetchResult> {
        if (chapters.isEmpty()) return emptyMap()

        val results = linkedMapOf<String, ChapterFetchResult>()
        val pending = mutableListOf<OrderedOnlineChapter>()
        val resultLock = Any()

        fun emit(result: ChapterFetchResult) {
            // Cache writes and progress updates happen in this callback, so keep
            // the result map and callback serialized as one completion operation.
            synchronized(resultLock) {
                results[result.chapter.chapterId] = result
                onDone(result)
            }
        }

        chapters.forEach { chapter ->
            val immediate = when {
                chapter.chapterId in skipIds -> ChapterFetchResult(
                    chapter = chapter,
                    title = null,
                    content = null,
                    status = "skip",
                )
                chapter.pending -> ChapterFetchResult(
                    chapter = chapter,
                    title = chapter.fallbackTitle,
                    content = null,
                    status = "pending",
                )
                chapter.access == ChapterAccess.LOCKED -> ChapterFetchResult(
                    chapter = chapter,
                    title = chapter.fallbackTitle,
                    content = null,
                    status = "locked",
                )
                else -> null
            }
            if (immediate == null) pending += chapter else emit(immediate)
        }

        if (pending.isEmpty()) return results

        val batches = pending.chunked(batchSize.coerceAtLeast(1))
        val pool = Executors.newFixedThreadPool(ONLINE_DOWNLOAD_WORKERS)
        val remaining = CountDownLatch(batches.size)
        val nextToSubmit = AtomicInteger(minOf(ONLINE_DOWNLOAD_WORKERS, batches.size))
        val callbackError = AtomicReference<Exception?>(null)
        val retryLimit = retries.coerceAtLeast(0)

        // OnlineTaskQueue installs the handler on its own worker. ThreadLocal
        // values do not cross this executor, so explicitly carry it into tasks.
        val verificationHandler = DownloadRequestVerification.currentHandler()

        fun submit(index: Int) {
            pool.execute {
                val batch = batches[index]
                try {
                    DownloadRequestVerification.withOptionalHandler(verificationHandler) {
                        val fetched = try {
                            fetcher(batch, retryLimit)
                        } catch (_: Exception) {
                            // Network retry exhaustion, API rejection,
                            // verification cancellation/expiry, and decode
                            // failures all become terminal batch errors. This
                            // lets the sliding window continue and mirrors the
                            // official failDown progress path.
                            null
                        }
                        if (fetched == null) {
                            batch.forEach { chapter ->
                                emit(
                                    ChapterFetchResult(
                                        chapter = chapter,
                                        title = chapter.fallbackTitle,
                                        content = null,
                                        status = "error",
                                    ),
                                )
                            }
                        } else {
                            batch.forEach { chapter ->
                                val response = fetched[chapter.chapterId]
                                emit(
                                    ChapterFetchResult(
                                        chapter = chapter,
                                        title = response?.title ?: chapter.fallbackTitle,
                                        content = response?.content,
                                        // A successful response may omit an
                                        // unavailable chapter; report it as fetch error.
                                        status = response?.status ?: "error",
                                    ),
                                )
                            }
                        }
                    }
                } catch (error: Exception) {
                    // onDone belongs to the caller and can fail independently
                    // of the HTTP batch. Preserve that failure for the caller
                    // after all in-flight tasks have drained.
                    callbackError.compareAndSet(null, error)
                } finally {
                    // A failed batch is terminal for this scheduler. This avoids
                    // stranding later batches behind a failed slot.
                    val next = nextToSubmit.getAndIncrement()
                    if (next < batches.size) submit(next)
                    remaining.countDown()
                }
            }
        }

        repeat(minOf(ONLINE_DOWNLOAD_WORKERS, batches.size), ::submit)
        try {
            remaining.await()
        } catch (error: InterruptedException) {
            pool.shutdownNow()
            Thread.currentThread().interrupt()
            throw CiweimaoException("下载等待被中断", error)
        } finally {
            pool.shutdown()
        }

        callbackError.get()?.let { throw it }
        return results
    }

    /** Returns null after a final failure; only network-layer failures retry. */
    private fun fetchBatch(
        client: CiweimaoClient,
        batch: List<OrderedOnlineChapter>,
        retryLimit: Int,
    ): Map<String, ChapterTextResult>? {
        val ids = batch.map(OrderedOnlineChapter::chapterId)
        var retryCount = 0
        while (true) {
            try {
                val command = client.getChapterDownloadCommand(ids, retries = 1)
                return client.downloadChapterBatch(ids, command, retries = 1)
            } catch (error: CiweimaoHttpException) {
                if (!error.retryable || retryCount >= retryLimit) return null
                retryCount += 1
            } catch (error: CiweimaoException) {
                // API rejection, verification cancellation/expiry, and decode
                // failures are not network failures. Preserve the existing
                // task-fatal semantics instead of silently marking them locked.
                throw error
            } catch (_: Exception) {
                // Non-protocol failures still terminate this batch without a
                // network replay; the remaining window may continue.
                return null
            }
        }
    }
}
