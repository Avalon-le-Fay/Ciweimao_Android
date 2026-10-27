package com.avalon.cwm.backend.server

/**
 * 给 index.html 里的本地静态资源引用（CSS/JS/图片/字体）打上安装版本号。
 * 资源由 App 内置 HTTP 服务从 APK 读取；发长缓存后必须靠版本号失效，
 * 否则更新 APK 后 WebView 还会继续用旧文件。
 */
internal object AssetVersioning {
    private val assetRef = Regex(
        "(href|src)=\"([^\"]+\\.(?:css|js|png|jpe?g|webp|gif|svg|ico|woff2?|ttf|otf))\"",
        RegexOption.IGNORE_CASE,
    )

    fun stamp(html: String, version: String): String {
        if (version.isBlank()) return html
        return assetRef.replace(html) { match ->
            val url = match.groupValues[2]
            when {
                url.contains("://") -> match.value
                url.startsWith("//") -> match.value
                url.contains('?') -> match.value
                else -> "${match.groupValues[1]}=\"$url?v=$version\""
            }
        }
    }
}
