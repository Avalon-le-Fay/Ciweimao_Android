package com.avalon.cwm.backend.core

import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

object FileTools {
    data class ImageType(val mime: String, val extension: String)

    fun sanitizeName(value: String): String {
        var result = value
        linkedMapOf(
            ':' to '：',
            '?' to '？',
            '"' to '”',
            '<' to '《',
            '>' to '》',
            '*' to ' ',
            '|' to ' ',
            '/' to ' ',
            '\\' to ' ',
        ).forEach { (before, after) -> result = result.replace(before, after) }
        return result.trim()
    }

    fun detectImage(bytes: ByteArray): ImageType {
        require(bytes.isNotEmpty()) { "图片识别 Mime 失败" }
        return when {
            startsWith(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ->
                ImageType("image/png", "png")
            startsWith(bytes, 0xff, 0xd8, 0xff) -> ImageType("image/jpeg", "jpg")
            asciiAt(bytes, 0, "GIF87a") || asciiAt(bytes, 0, "GIF89a") ->
                ImageType("image/gif", "gif")
            bytes.size >= 12 && asciiAt(bytes, 0, "RIFF") && asciiAt(bytes, 8, "WEBP") ->
                ImageType("image/webp", "webp")
            startsWith(bytes, 0x42, 0x4d) -> ImageType("image/bmp", "bmp")
            bytes.size >= 12 && asciiAt(bytes, 4, "ftyp") &&
                listOf("heic", "heix", "hevc", "hevx").any { asciiAt(bytes, 8, it) } ->
                ImageType("image/heic", "heic")
            bytes.size >= 12 && asciiAt(bytes, 4, "ftyp") &&
                listOf("mif1", "msf1").any { asciiAt(bytes, 8, it) } ->
                ImageType("image/heif", "heif")
            else -> throw IllegalArgumentException("图片识别 Mime 失败")
        }
    }

    fun isWithin(file: File, root: File): Boolean = try {
        val candidate = file.canonicalFile
        val base = root.canonicalFile
        candidate == base || candidate.path.startsWith(base.path + File.separator)
    } catch (_: Exception) {
        false
    }

    fun directOutputFile(outputRoot: File, rawName: String): File? {
        val name = rawName.trim()
        if (name.isEmpty() || name != File(name).name) return null
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension != "txt" && extension != "epub") return null
        val candidate = File(outputRoot, name)
        if (isSymbolicLink(candidate)) return null
        return candidate.takeIf {
            isWithin(it, outputRoot) && it.canonicalFile.parentFile == outputRoot.canonicalFile
        }
    }

    fun walkRegularFiles(root: File): List<Pair<String, File>> {
        if (!root.isDirectory || isSymbolicLink(root)) return emptyList()
        val canonicalRoot = root.canonicalFile
        val result = mutableListOf<Pair<String, File>>()
        fun visit(directory: File) {
            directory.listFiles()?.sortedBy { it.name }?.forEach { child ->
                if (isSymbolicLink(child) || !isWithin(child, canonicalRoot)) return@forEach
                when {
                    child.isDirectory -> visit(child)
                    child.isFile -> {
                        val relative = child.canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath
                        if (relative.isNotBlank() && !relative.startsWith("../")) {
                            result += relative to child
                        }
                    }
                }
            }
        }
        visit(canonicalRoot)
        return result
    }

    fun copyVerified(source: File, destination: File) {
        require(source.isFile && !isSymbolicLink(source)) {
            "源文件不存在或不是普通文件"
        }
        require(source.canonicalFile != destination.canonicalFile) { "源文件与目标文件相同" }
        destination.parentFile?.let(::ensureDirectory)
        source.inputStream().use { input ->
            FileOutputStream(destination, false).use { output -> input.copyTo(output) }
        }
        check(destination.isFile && destination.length() == source.length()) { "复制结果校验失败" }
    }

    fun ensureDirectory(directory: File) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建目录：${directory.absolutePath}" }
    }

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun isSymbolicLink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
    } catch (_: Exception) {
        false
    }

    private fun startsWith(bytes: ByteArray, vararg expected: Int): Boolean =
        bytes.size >= expected.size && expected.indices.all { (bytes[it].toInt() and 0xff) == expected[it] }

    private fun asciiAt(bytes: ByteArray, offset: Int, value: String): Boolean {
        val expected = value.toByteArray(StandardCharsets.US_ASCII)
        if (offset < 0 || bytes.size < offset + expected.size) return false
        return expected.indices.all { bytes[offset + it] == expected[it] }
    }
}

object JsonFiles {
    fun readObject(file: File): JSONObject? = try {
        file.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.let(::JSONObject)
    } catch (_: Exception) {
        null
    }

    fun readArray(file: File): JSONArray? = try {
        file.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.let(::JSONArray)
    } catch (_: Exception) {
        null
    }

    fun write(file: File, value: JSONObject) = writeTextAtomic(file, value.toString())

    fun write(file: File, value: JSONArray) = writeTextAtomic(file, value.toString())

    fun writeTextAtomic(file: File, value: String) {
        file.parentFile?.let(FileTools::ensureDirectory)
        val temporary = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            temporary.writeText(value, Charsets.UTF_8)
            Os.rename(temporary.absolutePath, file.absolutePath)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun stringList(file: File): MutableList<String> {
        val array = readArray(file) ?: return mutableListOf()
        val output = mutableListOf<String>()
        for (index in 0 until array.length()) {
            array.optString(index).trim().trimEnd('/', '\\').takeIf { it.isNotEmpty() }?.let {
                if (it !in output) output += it
            }
        }
        return output
    }

    fun writeStringList(file: File, values: Iterable<String>): List<String> {
        val distinct = values.map { it.trim().trimEnd('/', '\\') }
            .filter { it.isNotEmpty() }.distinct()
        write(file, JSONArray(distinct))
        return distinct
    }
}
