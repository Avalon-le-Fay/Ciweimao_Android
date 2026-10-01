package com.avalon.cwm

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通过 Root 从用户指定的绝对路径复制数据库、章节和密钥到本应用 cacheDir。
 *
 * 不提供通用命令执行接口：实际命令固定为定位并复制 novelCiwei、
 * booksnew 和 Y2hlcy8/Y2hlcy；路径只做命令注入与路径穿越防护。
 */
class RootCacheImporter {
    data class Session(
        val token: String,
        val databasePath: String,
        val booksPath: String,
        val keysPath: String,
    )

    interface Callback {
        fun onSuccess(session: Session)
        fun onFailure(message: String)
    }

    interface DeleteCallback {
        fun onSuccess()
        fun onFailure(message: String)
    }

    private data class SessionRecord(
        val directory: File,
        val databaseInput: String,
        val booksInput: String,
        val keysInput: String,
    )

    companion object {
        private const val MAX_OUTPUT_CHARS = 12_000
        private const val ROOT_TIMEOUT_MS = 60L * 60L * 1000L

    }

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "cwm-root-cache-import")
    }
    private val busy = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<String, SessionRecord>()

    fun isBusy(): Boolean = busy.get()

    fun start(
        context: Context,
        databaseInput: String,
        booksInput: String,
        keysInput: String,
        callback: Callback,
    ) {
        if (!busy.compareAndSet(false, true)) {
            callback.onFailure("已有 Root 导入任务正在运行")
            return
        }

        val appContext = context.applicationContext
        worker.execute {
            var stageDir: File? = null
            var busyReleased = false
            try {
                val databasePath = validateRootPath(databaseInput, "数据库路径")
                val booksPath = validateRootPath(booksInput, "加密章节路径")
                val keysPath = validateRootPath(keysInput, "密钥路径")

                val token = UUID.randomUUID().toString()
                val rootImportDir = File(appContext.cacheDir, "root_import")
                if (!rootImportDir.exists() && !rootImportDir.mkdirs()) {
                    error("无法创建 Root 导入缓存目录")
                }
                stageDir = File(rootImportDir, token)

                val script = buildCopyScript(
                    databasePath = databasePath,
                    booksPath = booksPath,
                    keysPath = keysPath,
                    stagePath = stageDir.absolutePath,
                    appUid = android.os.Process.myUid(),
                )
                val output = runSu(script)

                val stagedDatabase = File(stageDir, "database/novelCiwei")
                val stagedBooks = File(stageDir, "booksnew")
                val stagedKeys = File(stageDir, "Y2hlcy8")
                if (!stagedDatabase.isFile || !stagedDatabase.canRead()) {
                    error("Root 复制完成，但本应用无法读取暂存数据库")
                }
                if (!stagedBooks.isDirectory || !stagedBooks.canRead()) {
                    error("Root 复制完成，但本应用无法读取暂存章节目录")
                }
                if (!stagedKeys.isDirectory || !stagedKeys.canRead()) {
                    error("Root 复制完成，但本应用无法读取暂存密钥目录")
                }
                if (!output.contains("CWM_ROOT_COPY_OK")) {
                    error("Root 复制未返回完成标记")
                }

                sessions[token] = SessionRecord(
                    directory = stageDir,
                    databaseInput = databasePath,
                    booksInput = booksPath,
                    keysInput = keysPath,
                )
                val session = Session(
                    token = token,
                    databasePath = File(stageDir, "database").absolutePath,
                    booksPath = stagedBooks.absolutePath,
                    keysPath = stagedKeys.absolutePath,
                )
                stageDir = null // 成功后由网页完成 Kotlin 后端导入，再显式清理。
                busyReleased = true
                busy.set(false)
                callback.onSuccess(session)
            } catch (t: Throwable) {
                stageDir?.deleteRecursively()
                callback.onFailure(rootErrorMessage(t))
            } finally {
                if (!busyReleased) busy.set(false)
            }
        }
    }

    fun commitDelete(
        token: String,
        bookIds: List<String>,
        keyNames: List<String>,
        callback: DeleteCallback,
    ) {
        if (!token.matches(Regex("[0-9a-fA-F-]{36}"))) {
            callback.onFailure("Root 暂存令牌无效")
            return
        }
        val record = sessions[token]
        if (record == null) {
            callback.onFailure("Root 暂存数据不存在或已清理")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            callback.onFailure("已有 Root 缓存任务正在运行")
            return
        }
        worker.execute {
            try {
                val ids = bookIds.distinct()
                require(ids.isNotEmpty() && ids.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
                    "书籍 ID 无效"
                }
                val keys = keyNames.distinct()
                require(keys.all { it.matches(Regex("[A-Za-z0-9_+=.-]+")) }) {
                    "密钥文件名无效"
                }
                val output = runSu(
                    buildDeleteScript(
                        databasePath = record.databaseInput,
                        booksPath = record.booksInput,
                        keysPath = record.keysInput,
                        stagedDatabase = File(record.directory, "database/novelCiwei").absolutePath,
                        bookIds = ids,
                        keyNames = keys,
                    ),
                )
                check(output.contains("CWM_ROOT_DELETE_OK")) { "Root 删除未返回完成标记" }
                sessions.remove(token)
                record.directory.deleteRecursively()
                callback.onSuccess()
            } catch (error: Throwable) {
                callback.onFailure(rootErrorMessage(error))
            } finally {
                busy.set(false)
            }
        }
    }

    /** 只能删除由本对象创建并登记过的随机会话目录。 */
    fun cleanup(token: String) {
        if (!token.matches(Regex("[0-9a-fA-F-]{36}"))) return
        val record = sessions.remove(token) ?: return
        worker.execute { record.directory.deleteRecursively() }
    }

    private fun validateRootPath(input: String, label: String): String {
        val value = input.trim()
        require(value.isNotEmpty()) { "$label 为空" }
        require(value.startsWith('/')) { "$label 必须是绝对路径" }
        require(!value.contains('\u0000') && !value.contains('\n') && !value.contains('\r')) {
            "$label 包含非法字符"
        }

        val components = value.split('/').filter { it.isNotEmpty() }
        require(components.none { it == "." || it == ".." }) {
            "$label 不允许包含 . 或 .. 路径段"
        }
        val normalised = "/" + components.joinToString("/")
        return normalised
    }

    private fun buildCopyScript(
        databasePath: String,
        booksPath: String,
        keysPath: String,
        stagePath: String,
        appUid: Int,
    ): String = """
        set -eu
        DB_INPUT=${shellQuote(databasePath)}
        BOOKS_INPUT=${shellQuote(booksPath)}
        KEYS_INPUT=${shellQuote(keysPath)}
        STAGE=${shellQuote(stagePath)}

        DB_SOURCE=""
        if [ -f "${'$'}DB_INPUT" ]; then
            DB_SOURCE="${'$'}DB_INPUT"
        elif [ -f "${'$'}{DB_INPUT}/novelCiwei" ]; then
            DB_SOURCE="${'$'}{DB_INPUT}/novelCiwei"
        elif [ -f "${'$'}{DB_INPUT}/novelCiwei.db" ]; then
            DB_SOURCE="${'$'}{DB_INPUT}/novelCiwei.db"
        else
            echo "找不到数据库文件 novelCiwei 或 novelCiwei.db" >&2
            exit 21
        fi

        BOOKS_SOURCE=""
        if [ -d "${'$'}{BOOKS_INPUT}/booksnew" ]; then
            BOOKS_SOURCE="${'$'}{BOOKS_INPUT}/booksnew"
        elif [ -d "${'$'}BOOKS_INPUT" ] && [ "${'$'}(basename "${'$'}BOOKS_INPUT")" = "booksnew" ]; then
            BOOKS_SOURCE="${'$'}BOOKS_INPUT"
        else
            echo "找不到加密章节目录 booksnew" >&2
            exit 22
        fi

        KEYS_SOURCE=""
        if [ -d "${'$'}{KEYS_INPUT}/Y2hlcy8" ]; then
            KEYS_SOURCE="${'$'}{KEYS_INPUT}/Y2hlcy8"
        elif [ -d "${'$'}{KEYS_INPUT}/Y2hlcy" ]; then
            KEYS_SOURCE="${'$'}{KEYS_INPUT}/Y2hlcy"
        elif [ -d "${'$'}KEYS_INPUT" ] && { [ "${'$'}(basename "${'$'}KEYS_INPUT")" = "Y2hlcy8" ] || [ "${'$'}(basename "${'$'}KEYS_INPUT")" = "Y2hlcy" ]; }; then
            KEYS_SOURCE="${'$'}KEYS_INPUT"
        else
            echo "找不到密钥目录 Y2hlcy8 或 Y2hlcy" >&2
            exit 23
        fi

        rm -rf "${'$'}STAGE"
        mkdir -p "${'$'}STAGE/database" "${'$'}STAGE/booksnew" "${'$'}STAGE/Y2hlcy8"
        cp -f "${'$'}DB_SOURCE" "${'$'}STAGE/database/novelCiwei"
        for suffix in -wal -shm -journal; do
            if [ -f "${'$'}{DB_SOURCE}${'$'}{suffix}" ]; then
                cp -f "${'$'}{DB_SOURCE}${'$'}{suffix}" "${'$'}STAGE/database/novelCiwei${'$'}{suffix}"
            fi
        done
        cp -R "${'$'}BOOKS_SOURCE/." "${'$'}STAGE/booksnew/"
        cp -R "${'$'}KEYS_SOURCE/." "${'$'}STAGE/Y2hlcy8/"

        chown -R $appUid:$appUid "${'$'}STAGE"
        chmod -R 700 "${'$'}STAGE"
        if command -v restorecon >/dev/null 2>&1; then
            restorecon -RF "${'$'}STAGE" >/dev/null 2>&1 || true
        fi
        echo CWM_ROOT_COPY_OK
    """.trimIndent()

    private fun buildDeleteScript(
        databasePath: String,
        booksPath: String,
        keysPath: String,
        stagedDatabase: String,
        bookIds: List<String>,
        keyNames: List<String>,
    ): String {
        val deleteBooks = bookIds.joinToString("\n") { id ->
            "rm -rf \"${'$'}{BOOKS_SOURCE}/$id\""
        }
        val deleteKeys = keyNames.joinToString("\n") { name ->
            "rm -f \"${'$'}{KEYS_SOURCE}/$name\""
        }
        return """
            set -eu
            DB_INPUT=${shellQuote(databasePath)}
            BOOKS_INPUT=${shellQuote(booksPath)}
            KEYS_INPUT=${shellQuote(keysPath)}
            STAGED_DB=${shellQuote(stagedDatabase)}

            if [ -f "${'$'}DB_INPUT" ]; then DB_SOURCE="${'$'}DB_INPUT"
            elif [ -f "${'$'}{DB_INPUT}/novelCiwei" ]; then DB_SOURCE="${'$'}{DB_INPUT}/novelCiwei"
            elif [ -f "${'$'}{DB_INPUT}/novelCiwei.db" ]; then DB_SOURCE="${'$'}{DB_INPUT}/novelCiwei.db"
            else echo "找不到数据库文件" >&2; exit 31; fi

            if [ -d "${'$'}{BOOKS_INPUT}/booksnew" ]; then BOOKS_SOURCE="${'$'}{BOOKS_INPUT}/booksnew"
            elif [ -d "${'$'}BOOKS_INPUT" ] && [ "${'$'}(basename "${'$'}BOOKS_INPUT")" = "booksnew" ]; then BOOKS_SOURCE="${'$'}BOOKS_INPUT"
            else echo "找不到 booksnew" >&2; exit 32; fi

            if [ -d "${'$'}{KEYS_INPUT}/Y2hlcy8" ]; then KEYS_SOURCE="${'$'}{KEYS_INPUT}/Y2hlcy8"
            elif [ -d "${'$'}{KEYS_INPUT}/Y2hlcy" ]; then KEYS_SOURCE="${'$'}{KEYS_INPUT}/Y2hlcy"
            elif [ -d "${'$'}KEYS_INPUT" ] && { [ "${'$'}(basename "${'$'}KEYS_INPUT")" = "Y2hlcy8" ] || [ "${'$'}(basename "${'$'}KEYS_INPUT")" = "Y2hlcy" ]; }; then KEYS_SOURCE="${'$'}KEYS_INPUT"
            else echo "找不到密钥目录" >&2; exit 33; fi

            [ -f "${'$'}STAGED_DB" ] || { echo "修改后的暂存数据库不存在" >&2; exit 34; }
            cat "${'$'}STAGED_DB" > "${'$'}DB_SOURCE"
            rm -f "${'$'}{DB_SOURCE}-wal" "${'$'}{DB_SOURCE}-shm" "${'$'}{DB_SOURCE}-journal"
            $deleteBooks
            $deleteKeys
            echo CWM_ROOT_DELETE_OK
        """.trimIndent()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private data class RootCommandResult(val exitCode: Int, val output: String)

    private fun runSu(script: String): String {
        // Some rooted virtual Android systems grant uid 0 directly to app processes and have no
        // superuser manager. Others expose su only at an absolute system path which isn't in the
        // app process PATH. Support both without weakening the fixed-script/path restrictions.
        val guardedScript = """
            if [ "${'$'}(id -u)" != "0" ]; then
                echo CWM_ROOT_NOT_UID_0 >&2
                exit 126
            fi
            echo CWM_ROOT_UID_0
            $script
        """.trimIndent()

        val commands = mutableListOf<List<String>>()
        if (android.os.Process.myUid() == 0) {
            commands += listOf("/system/bin/sh", "-c", guardedScript)
        } else {
            val candidates = linkedSetOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/su/bin/su",
                "su",
            )
            for (executable in candidates) {
                if (executable == "su" || File(executable).canExecute()) {
                    commands += listOf(executable, "-c", guardedScript)
                    // A few manager-less VM implementations use the Android toolbox-style uid form.
                    commands += listOf(executable, "0", "sh", "-c", guardedScript)
                }
            }
        }

        if (commands.isEmpty()) {
            throw IllegalStateException(
                "未找到可执行的 Root shell；当前 APK 不是 UID 0，且常见 su 路径均不可用",
            )
        }

        var lastUnavailable = ""
        for (command in commands) {
            val result = try {
                runRootCommand(command)
            } catch (e: IOException) {
                lastUnavailable = "${command.first()}: ${e.message ?: "无法启动"}"
                continue
            }

            if (result.exitCode == 0) return result.output

            if (!result.output.contains("CWM_ROOT_UID_0")) {
                lastUnavailable = "${command.joinToString(" ")}: 未获得 UID 0" +
                    result.output.takeLast(500).takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                continue
            }

            // The command did become root, so this is a real copy/path failure, not a reason to
            // execute the same fixed script again through another su alias.
            val detail = result.output.takeLast(2_000)
                .ifBlank { "Root shell 返回状态码 ${result.exitCode}" }
            throw IllegalStateException("Root 读取失败：$detail")
        }

        throw IllegalStateException(
            "虚拟机未向本应用提供可用 Root shell；已尝试直接 UID 0 和常见 su 路径" +
                lastUnavailable.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty(),
        )
    }

    private fun runRootCommand(command: List<String>): RootCommandResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < MAX_OUTPUT_CHARS) {
                                output.append(line).append('\n')
                            }
                        }
                    }
                }
            } catch (_: IOException) {
            }
        }, "cwm-root-output").apply { isDaemon = true }
        reader.start()

        val deadline = System.currentTimeMillis() + ROOT_TIMEOUT_MS
        var exitCode: Int? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                exitCode = process.exitValue()
                break
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(250)
            }
        }

        if (exitCode == null) {
            process.destroy()
            throw IllegalStateException("Root shell 或复制等待超时")
        }
        reader.join(3_000)
        return RootCommandResult(
            exitCode = exitCode,
            output = synchronized(output) { output.toString().trim() },
        )
    }

    private fun rootErrorMessage(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        if (message.contains("未向本应用提供可用 Root shell") ||
            message.contains("未找到可执行的 Root shell")
        ) {
            return message
        }
        if (message.contains("denied", ignoreCase = true) ||
            message.contains("not allowed", ignoreCase = true) ||
            message.contains("permission", ignoreCase = true)
        ) {
            return "Root 权限被拒绝；请确认虚拟机已向本应用提供 UID 0 或可执行的 su"
        }
        return message.ifBlank { "Root 导入失败：${error.javaClass.simpleName}" }.take(2_500)
    }
}
