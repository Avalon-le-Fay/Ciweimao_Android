package com.avalon.cwm.backend.export

import com.avalon.cwm.backend.core.Book
import com.avalon.cwm.backend.core.FileTools
import com.avalon.cwm.backend.net.WebContentClient
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubWriter(
    private val web: WebContentClient,
) {
    data class Result(val chapters: Int, val images: Int)

    private data class ImageAsset(
        val id: String,
        val href: String,
        val mime: String,
        val bytes: ByteArray,
    )

    private data class ChapterAsset(
        val id: String,
        val href: String,
        val title: String,
        val divisionTitle: String,
        val body: String,
    )

    fun write(book: Book, destination: File, imageCache: File? = null): Result {
        destination.parentFile?.let(FileTools::ensureDirectory)
        imageCache?.let(FileTools::ensureDirectory)

        val chapterSources = book.divisions.flatMap { division ->
            division.chapters.map { chapter -> Triple(division.title, chapter.title, chapter.content) }
        }
        require(chapterSources.isNotEmpty()) { "没有可写入 EPUB 的章节" }

        val imageUrls = linkedSetOf<String>()
        chapterSources.forEach { (_, _, content) -> imageUrls += extractImageUrls(content) }
        val imagesByUrl = linkedMapOf<String, ImageAsset>()
        imageUrls.forEach { url ->
            val bytes = try {
                web.getBytes(url)
            } catch (_: Exception) {
                byteArrayOf()
            }
            if (bytes.isEmpty()) return@forEach
            val type = try {
                FileTools.detectImage(bytes)
            } catch (_: Exception) {
                return@forEach
            }
            val index = imagesByUrl.size
            val asset = ImageAsset(
                id = "img_$index",
                href = "images/img_$index.${type.extension}",
                mime = type.mime,
                bytes = bytes,
            )
            imagesByUrl[url] = asset
            imageCache?.let { cache ->
                runCatching { File(cache, "img_$index.${type.extension}").writeBytes(bytes) }
            }
        }

        val chapters = chapterSources.mapIndexed { index, (division, title, content) ->
            ChapterAsset(
                id = "chapter_${index + 1}",
                href = "chapters/chapter_${index + 1}.xhtml",
                title = title,
                divisionTitle = division,
                body = chapterBody(title, content, imagesByUrl),
            )
        }

        val identifier = "ciweimao_${book.id}_${UUID.randomUUID()}"
        val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
        try {
            ZipOutputStream(FileOutputStream(temporary)).use { zip ->
                putStored(zip, "mimetype", "application/epub+zip".toByteArray(StandardCharsets.US_ASCII))
                putText(zip, "META-INF/container.xml", containerXml())
                putText(zip, "OEBPS/styles/book.css", stylesheet())
                book.cover.takeIf { it.isNotEmpty() }?.let { cover ->
                    runCatching { FileTools.detectImage(cover) }.getOrNull()?.let { type ->
                        putBytes(zip, "OEBPS/images/cover.${type.extension}", cover)
                    }
                }
                imagesByUrl.values.forEach { image ->
                    putBytes(zip, "OEBPS/${image.href}", image.bytes)
                }
                chapters.forEach { chapter ->
                    putText(zip, "OEBPS/${chapter.href}", chapterXhtml(chapter))
                }
                putText(zip, "OEBPS/nav.xhtml", navigationXhtml(book, chapters))
                putText(zip, "OEBPS/toc.ncx", ncx(book, identifier, chapters))
                putText(zip, "OEBPS/content.opf", packageDocument(book, identifier, chapters, imagesByUrl.values))
            }
            if (destination.exists()) check(destination.delete()) { "无法替换旧 EPUB" }
            check(temporary.renameTo(destination)) { "无法保存 EPUB" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        return Result(chapters.size, imagesByUrl.size)
    }

    private fun extractImageUrls(raw: String): List<String> {
        val document = Jsoup.parseBodyFragment(replaceBookTags(raw))
        return document.select("img[src]").mapNotNull { image ->
            image.attr("src").trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.distinct()
    }

    private fun chapterBody(
        title: String,
        raw: String,
        images: Map<String, ImageAsset>,
    ): String {
        val document = Jsoup.parseBodyFragment(replaceBookTags(raw))
        document.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(StandardCharsets.UTF_8)
        document.select("script,style,iframe,object,embed").remove()
        document.select("span").unwrap()
        document.select("img").forEach { image ->
            val source = image.attr("src").trim()
            val asset = images[source]
            if (asset == null) {
                image.remove()
            } else {
                image.attr("src", "../${asset.href}")
                image.removeAttr("srcset")
                image.removeAttr("onerror")
                image.attr("alt", image.attr("alt").ifBlank { "插图" })
            }
        }

        val body = document.body()
        val hasBlockMarkup = body.select("p,div,section,blockquote,h1,h2,h3,h4,h5,h6,ul,ol,table,img").isNotEmpty()
        val markup = if (hasBlockMarkup) {
            body.html()
        } else {
            raw.lines().map(String::trim).filter(String::isNotEmpty)
                .joinToString("") { "<p>${escape(it)}</p>" }
        }
        return "<h1>${escape(title)}</h1>$markup"
    }

    private fun replaceBookTags(raw: String): String {
        val output = StringBuilder(raw.length)
        var cursor = 0
        while (cursor < raw.length) {
            val start = raw.indexOf("<Book", cursor)
            if (start < 0) {
                output.append(raw, cursor, raw.length)
                break
            }
            output.append(raw, cursor, start)
            var jsonStart = start + "<Book".length
            while (jsonStart < raw.length && raw[jsonStart].isWhitespace()) jsonStart += 1
            if (jsonStart >= raw.length || raw[jsonStart] != '{') {
                output.append("<Book")
                cursor = start + "<Book".length
                continue
            }
            val jsonEnd = findJsonObjectEnd(raw, jsonStart)
            if (jsonEnd < 0) {
                output.append(raw, start, raw.length)
                break
            }
            var tagEnd = jsonEnd + 1
            while (tagEnd < raw.length && raw[tagEnd].isWhitespace()) tagEnd += 1
            if (tagEnd >= raw.length || raw[tagEnd] != '>') {
                output.append(raw, start, jsonEnd + 1)
                cursor = jsonEnd + 1
                continue
            }
            val data = runCatching {
                JSONObject(raw.substring(jsonStart, jsonEnd + 1))
            }.getOrNull()
            if (data == null) {
                output.append(raw, start, tagEnd + 1)
            } else {
                output.append("此为作者推书，书名：")
                    .append(data.optString("book_name"))
                    .append("，ID：")
                    .append(data.optString("book_id"))
            }
            cursor = tagEnd + 1
        }
        return output.toString()
    }

    private fun findJsonObjectEnd(value: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until value.length) {
            val character = value[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == 92.toChar() -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                    if (depth < 0) return -1
                }
            }
        }
        return -1
    }

    private fun chapterXhtml(chapter: ChapterAsset): String = xmlHeader() + """
        <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN" lang="zh-CN">
          <head>
            <title>${escape(chapter.title)}</title>
            <link rel="stylesheet" type="text/css" href="../styles/book.css"/>
          </head>
          <body>${chapter.body}</body>
        </html>
    """.trimIndent()

    private fun navigationXhtml(book: Book, chapters: List<ChapterAsset>): String {
        val grouped = chapters.groupBy { it.divisionTitle }
        val list = buildString {
            append("<ol>")
            grouped.forEach { (division, items) ->
                append("<li><span>").append(escape(division)).append("</span><ol>")
                items.forEach { chapter ->
                    append("<li><a href=\"").append(chapter.href).append("\">")
                        .append(escape(chapter.title)).append("</a></li>")
                }
                append("</ol></li>")
            }
            append("</ol>")
        }
        return xmlHeader() + """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN" lang="zh-CN">
              <head><title>${escape(book.name)}</title></head>
              <body><nav epub:type="toc" id="toc"><h1>目录</h1>$list</nav></body>
            </html>
        """.trimIndent()
    }

    private fun ncx(book: Book, identifier: String, chapters: List<ChapterAsset>): String {
        val points = chapters.mapIndexed { index, chapter ->
            """
            <navPoint id="navPoint-${index + 1}" playOrder="${index + 1}">
              <navLabel><text>${escape(chapter.title)}</text></navLabel>
              <content src="${chapter.href}"/>
            </navPoint>
            """.trimIndent()
        }.joinToString("\n")
        return xmlHeader() + """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head><meta name="dtb:uid" content="${escape(identifier)}"/></head>
              <docTitle><text>${escape(book.name)}</text></docTitle>
              <navMap>$points</navMap>
            </ncx>
        """.trimIndent()
    }

    private fun packageDocument(
        book: Book,
        identifier: String,
        chapters: List<ChapterAsset>,
        images: Collection<ImageAsset>,
    ): String {
        val coverType = book.cover.takeIf { it.isNotEmpty() }
            ?.let { runCatching { FileTools.detectImage(it) }.getOrNull() }
        val manifest = buildString {
            append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
            append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>")
            append("<item id=\"style\" href=\"styles/book.css\" media-type=\"text/css\"/>")
            coverType?.let {
                append("<item id=\"cover-image\" href=\"images/cover.${it.extension}\" media-type=\"")
                    .append(it.mime).append("\" properties=\"cover-image\"/>")
            }
            images.forEach { image ->
                append("<item id=\"").append(image.id).append("\" href=\"")
                    .append(image.href).append("\" media-type=\"").append(image.mime).append("\"/>")
            }
            chapters.forEach { chapter ->
                append("<item id=\"").append(chapter.id).append("\" href=\"")
                    .append(chapter.href).append("\" media-type=\"application/xhtml+xml\"/>")
            }
        }
        val spine = chapters.joinToString("") { "<itemref idref=\"${it.id}\"/>" }
        return xmlHeader() + """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="zh-CN">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="book-id">${escape(identifier)}</dc:identifier>
                <dc:title>${escape(book.name)}</dc:title>
                <dc:creator>${escape(book.author)}</dc:creator>
                <dc:language>zh-CN</dc:language>
                <meta property="dcterms:modified">2000-01-01T00:00:00Z</meta>
              </metadata>
              <manifest>$manifest</manifest>
              <spine toc="ncx">$spine</spine>
            </package>
        """.trimIndent()
    }

    private fun containerXml(): String = xmlHeader() + """
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()

    private fun stylesheet(): String = """
        html { writing-mode: horizontal-tb; }
        body { margin: 5%; line-height: 1.75; }
        h1 { font-size: 1.45em; margin: 0 0 1.4em; }
        p { margin: 0.6em 0; text-indent: 2em; }
        img { display: block; max-width: 100%; height: auto; margin: 1em auto; }
    """.trimIndent()

    private fun putStored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) =
        putBytes(zip, name, text.toByteArray(StandardCharsets.UTF_8))

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun xmlHeader(): String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

}
