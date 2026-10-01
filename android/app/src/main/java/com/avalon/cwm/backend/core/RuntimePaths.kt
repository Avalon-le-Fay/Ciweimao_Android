package com.avalon.cwm.backend.core

import android.content.Context
import java.io.File

/**
 * Keeps the native backend on the same persistent directory used by the former
 * previous embedded runtime, so upgrading does not orphan imported caches or output files.
 */
class RuntimePaths(context: Context) {
    val root: File = File(context.filesDir, "cwm_runtime")
    val data: File = File(root, "data")
    val keys: File = File(data, "key")
    val output: File = File(root, "output")
    val chapterOutput: File = File(output, "txt")
    val outputCache: File = File(output, "cache")
    val cache: File = File(root, "cache")
    val textCache: File = File(cache, "texts")
    val imageCache: File = File(cache, "images")
    val database: File = File(data, "novelCiwei.db")

    init {
        listOf(
            root,
            data,
            keys,
            output,
            chapterOutput,
            outputCache,
            cache,
            textCache,
            imageCache,
        ).forEach { directory ->
            check(directory.isDirectory || directory.mkdirs()) {
                "无法创建运行目录：${directory.absolutePath}"
            }
        }
    }

    fun stateFile(name: String): File {
        require(name.startsWith('.') && !name.contains('/') && !name.contains('\\'))
        return File(root, name)
    }
}
