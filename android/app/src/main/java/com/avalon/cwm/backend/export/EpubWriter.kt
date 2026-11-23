package com.avalon.cwm.backend.export

import com.avalon.cwm.backend.core.Book
import com.avalon.cwm.backend.core.Chapter
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

    /** 一个分卷在 EPUB 里的独立标题页（目录里可点，正文里分页）。 */
    private data class VolumeAsset(
        val id: String,
        val href: String,
        val title: String,
    )

    private data class ChapterAsset(
        val id: String,
        val href: String,
        val title: String,
        val divisionTitle: String,
        val body: String,
    )

    /** 按目录顺序切出的连续段：一卷（或没有卷名的一组章节）。 */
    private data class Section(
        val volume: VolumeAsset?,
        val chapters: List<ChapterAsset>,
    )

    private data class DivisionContent(
        val title: String,
        val chapters: List<Chapter>,
    )

    fun write(book: Book, destination: File, imageCache: File? = null): Result {
        destination.parentFile?.let(FileTools::ensureDirectory)
        imageCache?.let(FileTools::ensureDirectory)

        val divisions = book.divisions.mapNotNull { division ->
            if (division.chapters.isEmpty()) return@mapNotNull null
            DivisionContent(division.title.trim(), division.chapters)
        }
        require(divisions.isNotEmpty()) { "没有可写入 EPUB 的章节" }

        val chapterSources = divisions.flatMap { it.chapters }
        val imageUrls = linkedSetOf<String>()
        chapterSources.forEach { chapter -> imageUrls += extractImageUrls(chapter.content) }
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

        var chapterIndex = 0
        var volumeIndex = 0
        val sections = divisions.map { division ->
            // 没有卷名的书不要生成空标题页，也不要在目录里套一层空节点。
            val volume = if (division.title.isEmpty()) {
                null
            } else {
                volumeIndex += 1
                VolumeAsset(
                    id = "volume_$volumeIndex",
                    href = "chapters/volume_$volumeIndex.xhtml",
                    title = division.title,
                )
            }
            val chapters = division.chapters.map { chapter ->
                chapterIndex += 1
                ChapterAsset(
                    id = "chapter_$chapterIndex",
                    href = "chapters/chapter_$chapterIndex.xhtml",
                    title = chapter.title,
                    divisionTitle = division.title,
                    body = chapterBody(chapter.title, chapter.content, imagesByUrl),
                )
            }
            Section(volume, chapters)
        }
        val chapters = sections.flatMap { it.chapters }

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
                sections.forEach { section ->
                    section.volume?.let { volume ->
                        putText(zip, "OEBPS/${volume.href}", volumeXhtml(volume))
                    }
                    section.chapters.forEach { chapter ->
                        putText(zip, "OEBPS/${chapter.href}", chapterXhtml(chapter))
                    }
                }
                putText(zip, "OEBPS/nav.xhtml", navigationXhtml(book, sections))
                putText(zip, "OEBPS/toc.ncx", ncx(book, identifier, sections))
                putText(zip, "OEBPS/content.opf", packageDocument(book, identifier, sections, imagesByUrl.values))
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
                    character == '\"' -> inString = false
                }
                continue
            }
            when (character) {
                '\"' -> inString = true
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

    private fun volumeXhtml(volume: VolumeAsset): String = xmlHeader() + """
        <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN" lang="zh-CN">
          <head>
            <title>${escape(volume.title)}</title>
            <link rel="stylesheet" type="text/css" href="../styles/book.css"/>
          </head>
          <body>
            <section class="volume-title">
              <h1>${escape(volume.title)}</h1>
            </section>
          </body>
        </html>
    """.trimIndent()

    private fun navigationXhtml(book: Book, sections: List<Section>): String {
        val list = buildString {
            append("<ol>")
            sections.forEach { section ->
                val volume = section.volume
                if (volume != null) {
                    append("<li><a href=\"").append(volume.href).append("\">")
                        .append(escape(volume.title)).append("</a><ol>")
                    section.chapters.forEach { chapter ->
                        append("<li><a href=\"").append(chapter.href).append("\">")
                            .append(escape(chapter.title)).append("</a></li>")
                    }
                    append("</ol></li>")
                } else {
                    section.chapters.forEach { chapter ->
                        append("<li><a href=\"").append(chapter.href).append("\">")
                            .append(escape(chapter.title)).append("</a></li>")
                    }
                }
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

    private fun ncx(book: Book, identifier: String, sections: List<Section>): String {
        var playOrder = 0
        val points = buildString {
            sections.forEach { section ->
                val volume = section.volume
                if (volume != null) {
                    playOrder += 1
                    append("<navPoint id=\"navPoint-$playOrder\" playOrder=\"$playOrder\">")
                        .append("<navLabel><text>").append(escape(volume.title)).append("</text></navLabel>")
                        .append("<content src=\"").append(volume.href).append("\"/>")
                    section.chapters.forEach { chapter ->
                        playOrder += 1
                        append("<navPoint id=\"navPoint-$playOrder\" playOrder=\"$playOrder\">")
                            .append("<navLabel><text>").append(escape(chapter.title)).append("</text></navLabel>")
                            .append("<content src=\"").append(chapter.href).append("\"/>")
                            .append("</navPoint>")
                    }
                    append("</navPoint>")
                } else {
                    section.chapters.forEach { chapter ->
                        playOrder += 1
                        append("<navPoint id=\"navPoint-$playOrder\" playOrder=\"$playOrder\">")
                            .append("<navLabel><text>").append(escape(chapter.title)).append("</text></navLabel>")
                            .append("<content src=\"").append(chapter.href).append("\"/>")
                            .append("</navPoint>")
                    }
                }
            }
        }
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
        sections: List<Section>,
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
            sections.mapNotNull { it.volume }.forEach { volume ->
                append("<item id=\"").append(volume.id).append("\" href=\"")
                    .append(volume.href).append("\" media-type=\"application/xhtml+xml\"/>")
            }
            sections.forEach { section ->
                section.chapters.forEach { chapter ->
                    append("<item id=\"").append(chapter.id).append("\" href=\"")
                        .append(chapter.href).append("\" media-type=\"application/xhtml+xml\"/>")
                }
            }
        }
        val spine = buildString {
            sections.forEach { section ->
                section.volume?.let { append("<itemref idref=\"").append(it.id).append("\"/>") }
                section.chapters.forEach { append("<itemref idref=\"").append(it.id).append("\"/>") }
            }
        }
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
        .volume-title { break-before: page; page-break-before: always; text-align: center; margin: 30% 8% 0; }
        .volume-title h1 { font-size: 1.9em; line-height: 1.45; margin: 0; }
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
