package com.avalon.cwm.backend.online

import com.avalon.cwm.backend.core.ChapterAccess
import com.avalon.cwm.backend.core.CiweimaoCrypto
import com.avalon.cwm.backend.online.latest.LatestCdn
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser

/** Read the catalogue shape used by each protocol generation. */
fun CiweimaoClient.getToc(bookId: String): JSONArray {
    val response = if (config.isLatest) {
        // This is the exact request shape observed in the 2.9.365 capture.
        call(
            "chapter/get_updated_chapter_by_division_new",
            mapOf("book_id" to bookId, "division_id" to "0"),
            method = "POST",
        )
    } else {
        call("chapter/get_updated_chapter_by_division_new", mapOf("book_id" to bookId))
    }
    val source = response.getJSONObject("data")
        .optJSONArray("chapter_list") ?: JSONArray()
    val divisions = JSONArray()
    for (divisionIndex in 0 until source.length()) {
        val division = source.optJSONObject(divisionIndex) ?: continue
        val sourceChapters = division.optJSONArray("chapter_list") ?: JSONArray()
        val chapters = JSONArray()
        for (chapterIndex in 0 until sourceChapters.length()) {
            val chapter = sourceChapters.optJSONObject(chapterIndex) ?: continue
            chapters.put(
                JSONObject()
                    .put("chapter_id", chapter.opt("chapter_id") ?: JSONObject.NULL)
                    .put("chapter_title", chapter.optString("chapter_title"))
                    .put("is_paid", chapter.opt("is_paid") ?: JSONObject.NULL)
                    .put("auth_access", chapter.opt("auth_access") ?: JSONObject.NULL)
                    .put("is_valid", chapter.opt("is_valid") ?: JSONObject.NULL)
                    .put(
                        "access",
                        ChapterAccess.from(
                            chapter.opt("is_paid"),
                            chapter.opt("auth_access"),
                        ).wireValue,
                    ),
            )
        }
        divisions.put(
            JSONObject()
                .put("division_id", division.opt("division_id") ?: JSONObject.NULL)
                .put("division_index", division.opt("division_index") ?: JSONObject.NULL)
                .put("division_name", division.optString("division_name"))
                .put("chapters", chapters),
        )
    }
    return divisions
}

/** The 2.9.365 app obtains chapter content through its official batch chain. */
fun CiweimaoClient.getChapterText(chapterId: String): ChapterTextResult {
    if (config.isLatest) {
        val command = getChapterDownloadCommand(listOf(chapterId))
        val result = downloadChapterBatch(listOf(chapterId), command)[chapterId]
            ?: ChapterTextResult(null, null, "error")
        completeChapterDownload()
        return result
    }

    val commandResponse = call("chapter/get_chapter_cmd", mapOf("chapter_id" to chapterId))
    val command = commandResponse.optJSONObject("data")?.optString("command").orEmpty()
    if (command.isEmpty()) return ChapterTextResult(null, null, "locked")

    val info = call(
        "chapter/get_cpt_ifm",
        mapOf("chapter_id" to chapterId, "chapter_command" to command),
    ).optJSONObject("data")?.optJSONObject("chapter_info") ?: JSONObject()
    val title = cleanChapterTitle(info.optString("chapter_title"))
    val access = ChapterAccess.from(info.opt("is_paid"), info.opt("auth_access"))
    if (access == ChapterAccess.LOCKED) return ChapterTextResult(title, null, "locked")

    val encrypted = info.optString("txt_content")
    if (encrypted.isEmpty()) return ChapterTextResult(title, null, "empty")
    var text = try {
        CiweimaoCrypto.decryptOnlineResponse(encrypted, command).toString(Charsets.UTF_8)
    } catch (error: Throwable) {
        throw CiweimaoDecryptException("章节正文解密失败", error)
    }
    text = normalizeChapterText(text)
    if (isUnreviewedChapter(text)) return ChapterTextResult(title, null, "pending")

    val authorSay = normalizeChapterText(info.optString("author_say").trim())
    if (authorSay.isNotEmpty()) {
        text = text.trimEnd() + "\n\n\n——作者的话——\n\n" + authorSay
    }
    return ChapterTextResult(title, text, "ok")
}

fun CiweimaoClient.getChapterDownloadCommand(
    chapterIds: List<String>,
    retries: Int = config.retries,
): String {
    require(chapterIds.isNotEmpty()) { "批量章节不能为空" }
    val response = call(
        "chapter/get_chapter_download_cmd",
        mapOf("chapter_id" to chapterIds.joinToString(",")),
        method = "POST",
        retries = retries,
    )
    return response.optJSONObject("data")?.optString("command").orEmpty()
        .ifEmpty { throw CiweimaoException("批量下载命令为空") }
}

/** Decode data.chapter_infos with SHA-256(batch-command) and map by chapter_id. */
fun CiweimaoClient.downloadChapterBatch(
    chapterIds: List<String>,
    command: String,
    retries: Int = config.retries,
): Map<String, ChapterTextResult> {
    require(chapterIds.isNotEmpty()) { "批量章节不能为空" }
    require(command.isNotEmpty()) { "批量下载命令不能为空" }
    val response = call(
        "chapter/download_cpt",
        mapOf(
            "chapter_command" to command,
            "chapter_id" to chapterIds.joinToString(","),
        ),
        method = "POST",
        retries = retries,
    )
    val encrypted = response.optJSONObject("data")?.optString("chapter_infos").orEmpty()
    if (encrypted.isEmpty()) throw CiweimaoDecryptException("批量章节数据为空")

    val infos = try {
        JSONArray(
            CiweimaoCrypto.decryptOnlineResponse(encrypted, command)
                .toString(Charsets.UTF_8),
        )
    } catch (error: Throwable) {
        throw CiweimaoDecryptException("批量章节数据解密失败", error)
    }

    val requested = chapterIds.toHashSet()
    val results = linkedMapOf<String, ChapterTextResult>()
    for (index in 0 until infos.length()) {
        val info = infos.optJSONObject(index) ?: continue
        val chapterId = info.opt("chapter_id")?.toString().orEmpty()
        if (chapterId.isEmpty() || chapterId !in requested) continue

        var text = info.optString("txt_content")
        var status = when {
            text.isEmpty() -> "empty"
            isUnreviewedChapter(text) -> "pending"
            else -> "ok"
        }
        if (status == "ok" &&
            (text.startsWith("http://") || text.startsWith("https://"))
        ) {
            text = try {
                LatestCdn.fetchText(text, config.userAgent())
            } catch (error: Throwable) {
                throw CiweimaoDecryptException("章节 CDN 读取失败", error)
            }
            text = normalizeChapterText(text)
            if (isUnreviewedChapter(text)) status = "pending"
        } else if (status == "ok") {
            text = normalizeChapterText(text)
            if (isUnreviewedChapter(text)) status = "pending"
        }

        if (status == "ok") {
            val authorSay = normalizeChapterText(info.optString("author_say").trim())
            if (authorSay.isNotEmpty()) {
                text = text.trimEnd() + "\n\n\n——作者的话——\n\n" + authorSay
            }
        }
        results[chapterId] = ChapterTextResult(
            title = cleanChapterTitle(info.optString("chapter_title"))
                .takeIf(String::isNotBlank),
            content = text.takeIf { status == "ok" },
            status = status,
        )
    }
    return results
}

fun CiweimaoClient.completeChapterDownload() {
    call("chapter/check_download_cpt", method = "POST")
}

/** Match the Python client: convert paragraph/break tags but preserve img tags. */
private fun normalizeChapterText(value: String): String {
    if (value.isEmpty()) return value
    val paragraph = Regex("</?p\\b[^>]*>", RegexOption.IGNORE_CASE)
    val breaks = Regex("<br\\b[^>]*>", RegexOption.IGNORE_CASE)
    val images = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    if (!paragraph.containsMatchIn(value) && !breaks.containsMatchIn(value)) return value

    val savedImages = mutableListOf<String>()
    val token = "\uFDD0CWM_IMG_"
    var normalized = images.replace(value) { match ->
        savedImages += match.value
        "$token${savedImages.lastIndex}\uFDD1"
    }
    normalized = paragraph.replace(normalized, "\n")
    normalized = breaks.replace(normalized, "\n")
    normalized = Parser.unescapeEntities(normalized, false)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    val lines = normalized.split('\n')
        .map { it.trim(' ', '\t') }
        .filter(String::isNotEmpty)
    normalized = lines.joinToString("\n")
    savedImages.forEachIndexed { index, image ->
        normalized = normalized.replace("$token$index\uFDD1", image)
    }
    return normalized
}

private fun cleanChapterTitle(value: String): String =
    value.replace(Regex("#[0-9A-Za-z]{3,8}$"), "").trim()

private fun isUnreviewedChapter(value: String): Boolean {
    val text = value.trim()
    if (text.length <= 40 && CIWEIMAO_UNREVIEWED_MARKERS.any(text::contains)) return true
    val compact = text.replace("\n", "").replace("　", "").replace(" ", "")
    return CIWEIMAO_UNREVIEWED_MARKERS.any { marker ->
        compact == marker || compact == marker + marker
    }
}
