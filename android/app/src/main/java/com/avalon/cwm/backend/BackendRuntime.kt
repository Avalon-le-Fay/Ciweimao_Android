package com.avalon.cwm.backend

import android.content.Context
import com.avalon.cwm.backend.core.RuntimePaths
import com.avalon.cwm.backend.export.EpubWriter
import com.avalon.cwm.backend.local.CacheRepository
import com.avalon.cwm.backend.local.LocalDecryptService
import com.avalon.cwm.backend.net.WebContentClient
import com.avalon.cwm.backend.online.OnlineAccountRepository
import com.avalon.cwm.backend.online.OnlineBookDownloader
import com.avalon.cwm.backend.online.OnlineTaskQueue
import com.avalon.cwm.backend.output.OutputRepository
import com.avalon.cwm.backend.output.OutputSettingsRepository
import java.util.concurrent.atomic.AtomicLong

class BackendRuntime(
    context: Context,
    private val reportStartupStage: (String) -> Unit = {},
) {
    val paths = initialize("初始化运行目录") {
        RuntimePaths(context.applicationContext)
    }
    val revision = AtomicLong(0)
    val web = initialize("初始化通用网络客户端") {
        WebContentClient()
    }
    val epub = initialize("初始化 EPUB 写入器") {
        EpubWriter(web)
    }
    val cache = initialize("初始化 SQLite 与缓存仓库") {
        CacheRepository(paths)
    }
    val accounts = initialize("初始化在线账号仓库") {
        OnlineAccountRepository(paths, context.applicationContext)
    }
    val outputSettings = initialize("初始化输出设置仓库") {
        OutputSettingsRepository(paths)
    }

    val localDecrypt = initialize("初始化本地解密队列") {
        LocalDecryptService(
            paths = paths,
            web = web,
            epubWriter = epub,
            onOutputsChanged = ::bumpRevision,
        )
    }

    private val onlineBookDownloader = initialize("初始化在线单本下载器") {
        OnlineBookDownloader(
            paths = paths,
            accounts = accounts,
            web = web,
            epubWriter = epub,
        )
    }

    val onlineTasks = initialize("初始化在线多任务队列") {
        OnlineTaskQueue(
            downloader = onlineBookDownloader,
            onOutputsChanged = ::bumpRevision,
        )
    }

    val outputs = initialize("初始化输出与 ZIP 仓库") {
        OutputRepository(
            paths = paths,
            settings = outputSettings,
            outputGenerationRunning = {
                localDecrypt.isRunning() || onlineTasks.isRunning()
            },
            onChanged = ::bumpRevision,
        )
    }

    fun bumpRevision(): Long = revision.incrementAndGet()

    private fun <T> initialize(stage: String, block: () -> T): T {
        reportStartupStage(stage)
        return block()
    }
}
