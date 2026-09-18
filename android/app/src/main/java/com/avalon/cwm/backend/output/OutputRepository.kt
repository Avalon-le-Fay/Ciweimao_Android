package com.avalon.cwm.backend.output

import com.avalon.cwm.backend.core.FileTools
import com.avalon.cwm.backend.core.JsonFiles
import com.avalon.cwm.backend.core.RuntimePaths
import com.avalon.cwm.backend.local.CiweimaoDatabase
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OutputRepository(
    private val paths: RuntimePaths,
    private val settings: OutputSettingsRepository,
    private val outputGenerationRunning: () -> Boolean,
    private val onChanged: () -> Unit,
) {
    private val prepared = ConcurrentHashMap<String, PreparedDownload>()
    private val expiryWorker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "cwm-download-expiry")
    }

    init {
        cleanupOrphanedArchives()
    }

    fun select(rawNames: Iterable<Any?>): Pair<List<SelectedOutput>, List<String>> {
        val files = mutableListOf<SelectedOutput>()
        val missing = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        rawNames.forEach { raw ->
            val name = raw as? String
            if (name.isNullOrEmpty()) {
                missing += raw?.toString().orEmpty()
                return@forEach
            }
            if (!seen.add(name)) return@forEach
            val file = FileTools.directOutputFile(paths.output, name)
            if (file == null || !file.isFile) {
                missing += name
            } else {
                files += SelectedOutput(file.name, file)
            }
        }
        return files to missing
    }

    fun optionalPlan(
        files: List<SelectedOutput>,
        options: OutputOptions,
    ): OptionalOutputPlan {
        val directories = mutableListOf<String>()
        val entries = mutableListOf<OptionalOutputEntry>()
        val missing = mutableListOf<String>()
        val stems = files.map(SelectedOutput::stem).distinct()
        val kinds = buildList {
            if (options.chapters) add("chapters" to "逐章目录")
            if (options.images) add("images" to "图片")
        }
        kinds.forEach { (kind, displayDirectory) ->
            stems.forEach { stem ->
                val targetDirectory = "$displayDirectory/$stem"
                directories += targetDirectory
                val sourceRoot = optionalSourceDirectory(stem, kind)
                if (sourceRoot == null) {
                    missing += targetDirectory
                } else {
                    FileTools.walkRegularFiles(sourceRoot).forEach { (relative, source) ->
                        entries += OptionalOutputEntry(
                            archivePath = "$targetDirectory/$relative",
                            source = source,
                        )
                    }
                }
            }
        }
        return OptionalOutputPlan(directories, entries, missing)
    }

    private fun optionalSourceDirectory(stem: String, kind: String): File? {
        val candidates = when (kind) {
            "chapters" -> listOfNotNull(directChild(paths.chapterOutput, stem))
            "images" -> buildList {
                directChild(paths.imageCache, stem)?.let { add(it) }
                bookIdForSafeName(stem)?.let { bookId ->
                    add(File(File(File(paths.data, "decrypted"), bookId), "images"))
                }
            }
            else -> emptyList()
        }
        return candidates.firstOrNull { candidate ->
            candidate.isDirectory &&
                !FileTools.isSymbolicLink(candidate) &&
                FileTools.isWithin(candidate, paths.root)
        }
    }

    private fun directChild(root: File, leaf: String): File? {
        if (leaf.isBlank() || leaf == "." || leaf == ".." || leaf.contains('/') || leaf.contains(92.toChar())) return null
        val candidate = File(root, leaf)
        return candidate.takeIf {
            runCatching { it.canonicalFile.parentFile == root.canonicalFile }.getOrDefault(false)
        }
    }

    private fun bookIdForSafeName(safeName: String): String? {
        val withoutMetadata = mutableListOf<File>()
        paths.outputCache.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
            val metadata = JsonFiles.readObject(File(directory, ".meta.json"))
            if (metadata != null) {
                if (metadata.optString("safe") == safeName) return directory.name
            } else {
                withoutMetadata += directory
            }
        }
        if (withoutMetadata.isEmpty()) return null
        val database = runCatching { CiweimaoDatabase.openReadOnly(paths.database) }.getOrNull()
            ?: return null
        return database.use { db ->
            withoutMetadata.firstOrNull { directory ->
                val id = directory.name.toLongOrNull() ?: return@firstOrNull false
                FileTools.sanitizeName(db.getBookInfo(id).optString("book_name")) == safeName
            }?.name
        }
    }

    fun buildArchive(
        files: List<SelectedOutput>,
        options: OutputOptions,
    ): Pair<File, List<String>> {
        require(files.isNotEmpty()) { "未选择要下载的文件" }
        val archive = File.createTempFile("cwm-outputs-", ".zip", paths.root)
        try {
            val plan = optionalPlan(files, options)
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                files.forEach { selected ->
                    addZipFile(zip, selected.name, selected.file)
                }
                val seenDirectories = mutableSetOf<String>()
                plan.directories.forEach { directory ->
                    val parent = directory.substringBefore('/') + "/"
                    val child = directory.trimEnd('/') + "/"
                    if (seenDirectories.add(parent)) addEmptyZipDirectory(zip, parent)
                    if (seenDirectories.add(child)) addEmptyZipDirectory(zip, child)
                }
                plan.entries.forEach { entry ->
                    addZipFile(zip, entry.archivePath, entry.source)
                }
            }
            return archive to plan.missing
        } catch (error: Throwable) {
            archive.delete()
            throw error
        }
    }

    fun prepareDownload(
        files: List<SelectedOutput>,
        options: OutputOptions,
    ): Pair<PreparedDownload, List<String>> {
        val (archive, missing) = buildArchive(files, options)
        val token = UUID.randomUUID().toString().replace("-", "")
        val item = PreparedDownload(
            token = token,
            file = archive,
            filename = "cwm-outputs.zip",
            mimeType = "application/zip",
            expiresAtMillis = System.currentTimeMillis() + PREPARED_TTL_MILLIS,
        )
        prepared[token] = item
        expiryWorker.schedule(
            { expirePreparedDownload(token) },
            PREPARED_TTL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        return item to missing
    }

    fun preparedDownload(token: String): PreparedDownload? {
        val item = prepared[token] ?: return null
        if (item.expired || !item.file.isFile) {
            expirePreparedDownload(token)
            return null
        }
        return item
    }

    fun preparedTransferClosed(token: String) {
        if (!prepared.containsKey(token)) return
        expiryWorker.schedule(
            { expirePreparedDownload(token) },
            TRANSFER_CLOSE_GRACE_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun completePreparedDownload(token: String) {
        expirePreparedDownload(token)
    }

    private fun cleanupOrphanedArchives() {
        paths.root.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("cwm-outputs-") && file.extension == "zip") {
                file.delete()
            }
        }
    }

    private fun expirePreparedDownload(token: String) {
        prepared.remove(token)?.file?.delete()
    }

    private fun addEmptyZipDirectory(zip: ZipOutputStream, rawName: String) {
        val name = rawName.trimStart('/').replace('\\', '/').trimEnd('/') + "/"
        require(name.isNotBlank() && !name.startsWith("../") && "/../" !in name) { "归档目录无效" }
        zip.putNextEntry(ZipEntry(name))
        zip.closeEntry()
    }

    private fun addZipFile(zip: ZipOutputStream, rawName: String, source: File) {
        require(source.isFile && !FileTools.isSymbolicLink(source)) { "归档源文件无效" }
        val name = rawName.trimStart('/').replace('\\', '/')
        require(name.isNotBlank() && !name.startsWith("../") && "/../" !in name) {
            "归档路径无效"
        }
        zip.putNextEntry(ZipEntry(name))
        source.inputStream().buffered().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    fun deleteOutputs(rawNames: Iterable<Any?>): JSONObject {
        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val purged = mutableListOf<String>()
        rawNames.forEach { raw ->
            val name = raw?.toString().orEmpty()
            val file = FileTools.directOutputFile(paths.output, name)
            if (file == null || !file.isFile || !file.delete()) {
                failed += name
            } else {
                deleted += file.name
                purged += purgeResidue(file.nameWithoutExtension)
            }
        }
        if (deleted.isNotEmpty() || purged.isNotEmpty()) onChanged()
        return JSONObject()
            .put("ok", true)
            .put("deleted", org.json.JSONArray(deleted))
            .put("failed", org.json.JSONArray(failed))
            .put("purged", org.json.JSONArray(purged))
    }

    private fun purgeResidue(stem: String): List<String> {
        val purged = mutableListOf<String>()
        removeGeneratedDirectory(paths.chapterOutput, stem, "txt/$stem", purged)
        removeGeneratedDirectory(paths.textCache, stem, "cache/texts/$stem", purged)
        removeGeneratedDirectory(paths.imageCache, stem, "cache/images/$stem", purged)
        bookIdForSafeName(stem)?.let { bookId ->
            removeGeneratedDirectory(paths.outputCache, bookId, "cache/$bookId", purged)
        }
        return purged
    }

    private fun removeGeneratedDirectory(
        root: File,
        leaf: String,
        label: String,
        purged: MutableList<String>,
    ) {
        val directory = File(root, leaf)
        if (!directory.isDirectory ||
            FileTools.isSymbolicLink(directory) ||
            !FileTools.isWithin(directory, root) ||
            directory.canonicalFile == root.canonicalFile
        ) {
            return
        }
        if (directory.deleteRecursively()) purged += label
    }

    fun exportToBackend(
        files: List<SelectedOutput>,
        failedSelections: List<String>,
        destinationPath: String,
        options: OutputOptions,
    ): OutputExportResult {
        val destination = File(destinationPath.trim().trimEnd('/', '\\'))
        require(destination.path.isNotBlank()) { "未指定目录" }
        val autoCleanup = settings.autoCleanup()
        if (autoCleanup && FileTools.isWithin(destination, paths.output)) {
            throw IllegalArgumentException("自动清理已开启，导出目录不能位于项目 output/ 内")
        }
        if (autoCleanup && outputGenerationRunning()) {
            throw IllegalStateException("当前仍有下载或解密任务写入 output/，请等待任务完成后再导出清理")
        }
        FileTools.ensureDirectory(destination)

        val copied = mutableListOf<String>()
        val failed = failedSelections.toMutableList()
        val failedDetails = failedSelections.mapTo(mutableListOf()) {
            OutputFailure(it, "选中的文件不存在")
        }
        val copiedFiles = mutableListOf<SelectedOutput>()
        files.forEach { selected ->
            try {
                FileTools.copyVerified(selected.file, File(destination, selected.name))
                copied += selected.name
                copiedFiles += selected
            } catch (error: Exception) {
                failed += selected.name
                failedDetails += OutputFailure(
                    selected.name,
                    error.message?.take(500) ?: error.javaClass.simpleName,
                )
            }
        }

        val plan = optionalPlan(copiedFiles, options)
        val optionalCopied = mutableListOf<String>()
        val optionalFailed = mutableListOf<String>()
        plan.directories.forEach { relative ->
            val target = File(destination, relative)
            if (!FileTools.isWithin(target, destination)) {
                optionalFailed += relative
            } else {
                runCatching { FileTools.ensureDirectory(target) }
                    .onFailure { optionalFailed += relative }
            }
        }
        plan.entries.forEach { entry ->
            val target = File(destination, entry.archivePath)
            if (!FileTools.isWithin(target, destination) ||
                FileTools.isWithin(entry.source, destination)
            ) {
                optionalFailed += entry.archivePath
            } else {
                try {
                    FileTools.copyVerified(entry.source, target)
                    optionalCopied += entry.archivePath
                } catch (_: Exception) {
                    optionalFailed += entry.archivePath
                }
            }
        }

        val cleaned = mutableListOf<String>()
        val cleanupFailed = mutableListOf<String>()
        val purged = mutableListOf<String>()
        if (autoCleanup) {
            if (outputGenerationRunning() || optionalFailed.isNotEmpty() || plan.missing.isNotEmpty()) {
                cleanupFailed += copied
            } else {
                copied.forEach { name ->
                    val source = FileTools.directOutputFile(paths.output, name)
                    if (source != null && source.isFile && source.delete()) {
                        cleaned += name
                        purged += purgeResidue(source.nameWithoutExtension)
                    } else {
                        cleanupFailed += name
                    }
                }
            }
        }
        runCatching { JsonFiles.writeTextAtomic(paths.stateFile(".export_dir"), destination.path) }
        if (copied.isNotEmpty() || optionalCopied.isNotEmpty() || cleaned.isNotEmpty() || purged.isNotEmpty()) {
            onChanged()
        }
        return OutputExportResult(
            copied = copied,
            failed = failed,
            failedDetails = failedDetails,
            destination = destination.path,
            options = options,
            optionalCopied = optionalCopied,
            optionalFailed = optionalFailed,
            optionalMissing = plan.missing,
            autoCleanup = autoCleanup,
            cleaned = cleaned,
            cleanupFailed = cleanupFailed,
            purged = purged,
        )
    }

    companion object {
        private const val PREPARED_TTL_MILLIS = 10L * 60L * 1000L
        private const val TRANSFER_CLOSE_GRACE_MILLIS = 60L * 1000L
    }
}
