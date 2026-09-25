package com.avalon.cwm.backend.output

import com.avalon.cwm.backend.core.JsonFiles
import com.avalon.cwm.backend.core.RuntimePaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OutputSettingsRepository(private val paths: RuntimePaths) {
    private val exportDirectoryFile = paths.stateFile(".export_dir")
    private val autoCleanupFile = paths.stateFile(".export_auto_cleanup")
    private val favouriteDirectoriesFile = paths.stateFile(".export_favdirs")

    fun exportDirectory(): String = exportDirectoryFile
        .takeIf(File::isFile)
        ?.readText(Charsets.UTF_8)
        ?.trim()
        .orEmpty()

    fun saveExportDirectory(rawPath: String): String {
        val value = normalise(rawPath)
        require(value.isNotEmpty()) { "目录不能为空" }
        val directory = File(value)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalArgumentException("目录无法创建: $value")
        }
        val testFile = File(directory, ".cwm_write_test")
        try {
            testFile.writeText("ok", Charsets.UTF_8)
        } catch (error: Throwable) {
            throw IllegalArgumentException("目录不可写: ${error.message}", error)
        } finally {
            if (testFile.exists()) testFile.delete()
        }
        JsonFiles.writeTextAtomic(exportDirectoryFile, value)
        return value
    }

    fun autoCleanup(): Boolean = try {
        autoCleanupFile.isFile && autoCleanupFile.readText(Charsets.UTF_8).trim() == "true"
    } catch (_: Exception) {
        false
    }

    fun saveAutoCleanup(enabled: Boolean) {
        JsonFiles.writeTextAtomic(autoCleanupFile, if (enabled) "true" else "false")
    }

    fun favouriteDirectories(): MutableList<String> =
        JsonFiles.stringList(favouriteDirectoriesFile)

    fun addFavouriteDirectory(rawPath: String): List<String> {
        val path = normalise(rawPath)
        require(path.isNotEmpty()) { "目录不能为空" }
        val values = favouriteDirectories()
        if (path !in values) values += path
        return JsonFiles.writeStringList(favouriteDirectoriesFile, values)
    }

    fun removeFavouriteDirectory(rawPath: String): List<String> {
        val path = normalise(rawPath)
        return JsonFiles.writeStringList(
            favouriteDirectoriesFile,
            favouriteDirectories().filterNot { it == path },
        )
    }

    fun suggestions(): List<String> {
        val saved = exportDirectory()
        val user = favouriteDirectories()
        val defaults = listOf(
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Documents",
            "/storage/emulated/0/Books",
        )
        return buildList {
            if (saved.isNotEmpty()) add(saved)
            user.forEach { if (it !in this) add(it) }
            defaults.forEach { if (it !in this) add(it) }
        }
    }

    fun settingsJson(): JSONObject = JSONObject()
        .put("dir", exportDirectory())
        .put("suggestions", JSONArray(suggestions()))
        .put("auto_cleanup", autoCleanup())

    private fun normalise(rawPath: String): String {
        val value = rawPath.trim()
        return if (value == File.separator) value else value.trimEnd('/', '\\')
    }
}
