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
    private val hiddenDefaultsFile = paths.stateFile(".export_favdirs_hidden")
    private val defaultDirectories = listOf(
        "/storage/emulated/0/Download",
        "/storage/emulated/0/Documents",
        "/storage/emulated/0/Books",
    )

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
        val hidden = hiddenDefaults()
        if (value in hidden) {
            hidden.removeAll { it == value }
            JsonFiles.writeStringList(hiddenDefaultsFile, hidden)
        }
        val favourites = favouriteDirectories()
        if (value !in favourites) {
            JsonFiles.writeStringList(favouriteDirectoriesFile, favourites + value)
        }
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
        val hidden = hiddenDefaults()
        if (path in hidden) {
            hidden.removeAll { it == path }
            JsonFiles.writeStringList(hiddenDefaultsFile, hidden)
        }
        val values = favouriteDirectories()
        if (path !in values) values += path
        JsonFiles.writeStringList(favouriteDirectoriesFile, values)
        return suggestions()
    }

    fun removeFavouriteDirectory(rawPath: String): List<String> {
        val path = normalise(rawPath)
        if (path in defaultDirectories) {
            val hidden = hiddenDefaults()
            if (path !in hidden) hidden += path
            JsonFiles.writeStringList(hiddenDefaultsFile, hidden)
        }
        JsonFiles.writeStringList(
            favouriteDirectoriesFile,
            favouriteDirectories().filterNot { it == path },
        )
        return suggestions()
    }

    fun suggestions(): List<String> {
        val user = favouriteDirectories()
        val hidden = hiddenDefaults()
        return buildList {
            user.forEach { if (it !in this) add(it) }
            defaultDirectories.forEach { if (it !in hidden && it !in this) add(it) }
        }
    }

    private fun hiddenDefaults(): MutableList<String> = JsonFiles.stringList(hiddenDefaultsFile)

    fun settingsJson(): JSONObject = JSONObject()
        .put("dir", exportDirectory())
        .put("suggestions", JSONArray(suggestions()))
        .put("auto_cleanup", autoCleanup())

    private fun normalise(rawPath: String): String {
        val value = rawPath.trim()
        return if (value == File.separator) value else value.trimEnd('/', '\\')
    }
}
