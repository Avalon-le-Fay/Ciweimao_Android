package com.avalon.cwm

/**
 * 封面下载用的图片类型识别。
 *
 * 图床返回的 Content-Type 经常是 application/octet-stream 或者干脆缺失，
 * 直接按它命名会存出打不开的 .jpg；这里按文件头判断真实格式。
 */
internal object ImageSniffer {
    data class Type(val extension: String, val mimeType: String)

    fun sniff(bytes: ByteArray): Type {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) {
            return Type("jpg", "image/jpeg")
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        ) {
            return Type("png", "image/png")
        }
        if (bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) {
            return Type("webp", "image/webp")
        }
        if (bytes.size >= 6 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF") {
            return Type("gif", "image/gif")
        }
        return Type("jpg", "image/jpeg")
    }
}
