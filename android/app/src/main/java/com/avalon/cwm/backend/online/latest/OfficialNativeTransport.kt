package com.avalon.cwm.backend.online.latest

import android.content.Context
import android.os.Build
import android.util.Base64
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

internal data class OfficialHttpResponse(
    val statusCode: Int,
    val ciphertext: String,
)

/** Adapter for the verified official 2.9.365 libcurl/OpenSSL ABI closure. */
internal object OfficialNativeTransport {
    private const val MAX_API_RESPONSE_BYTES = 128 * 1024 * 1024

    private val expectedLibraries = mapOf(
        "arm64-v8a" to mapOf(
            "libcurl.so" to "e1ebe20ab8e109e583a980c2b9ab5e8bc1392f07e395fb8842907c75f037c7bc",
            "libssl.so" to "c4a044967b2bc94127e7c007593a8662cfda9be6445d743ebd35bfd801756948",
            "libcrypto.so" to "e3aeb89e5505410d9a9966415c0a59ccdd47ea5b2183aa28458b4570dfb07ce1",
        ),
        "armeabi-v7a" to mapOf(
            "libcurl.so" to "d8c7c6eaff6e61111b2ed2649e32797647a73bb352e507402aaf267cf718c139",
            "libssl.so" to "92e3b9925b396f8fd4b7413d7b38b4d1a1d5b31bffaf5ed696420b86b5c0d5f2",
            "libcrypto.so" to "e3980fa27800c42d6169e25cf5f0b4f6bf51ce59f4f6d9cf83cdf12b29ce61a3",
        ),
    )

    @Volatile
    private var loaded = false

    @Volatile
    private var verifiedApk: String? = null

    fun post(
        context: Context,
        path: String,
        userAgent: String,
        body: ByteArray,
        timeoutSeconds: Long,
    ): OfficialHttpResponse {
        val appContext = context.applicationContext
        verifyOfficialLibraries(appContext)
        ensureLibrariesLoaded()
        val caBundle = ensureCaBundle(appContext)
        val timeout = timeoutSeconds.coerceIn(1L, 300L) * 1000L
        val envelope = nativePost(
            path = "/" + path.trimStart('/'),
            userAgent = userAgent,
            caBundlePath = caBundle.absolutePath,
            body = body,
            connectTimeoutMillis = timeout.coerceAtMost(30_000L).toInt(),
            requestTimeoutMillis = timeout.toInt(),
            maximumResponseBytes = MAX_API_RESPONSE_BYTES,
        )
        if (envelope.size < 4) throw IOException("native HTTP 响应封装无效")
        val status = ((envelope[0].toInt() and 0xff) shl 24) or
            ((envelope[1].toInt() and 0xff) shl 16) or
            ((envelope[2].toInt() and 0xff) shl 8) or
            (envelope[3].toInt() and 0xff)
        val ciphertext = envelope.copyOfRange(4, envelope.size)
            .toString(StandardCharsets.US_ASCII)
        return OfficialHttpResponse(status, ciphertext)
    }

    @Synchronized
    private fun verifyOfficialLibraries(context: Context) {
        val apk = context.applicationInfo.sourceDir
        if (verifiedApk == apk) return
        val abi = Build.SUPPORTED_ABIS.firstOrNull(expectedLibraries::containsKey)
            ?: throw IOException("当前 ABI 不支持 2.9.365 official_native")
        val expected = requireNotNull(expectedLibraries[abi])
        ZipFile(apk).use { archive ->
            expected.forEach { (name, digest) ->
                val entry = archive.getEntry("lib/$abi/$name")
                    ?: throw IOException("APK 缺少官方传输库: $abi/$name")
                val actual = archive.getInputStream(entry).use(::sha256)
                if (!actual.equals(digest, ignoreCase = true)) {
                    throw IOException("官方传输库完整性校验失败: $abi/$name")
                }
            }
        }
        verifiedApk = apk
    }

    @Synchronized
    private fun ensureLibrariesLoaded() {
        if (loaded) return
        System.loadLibrary("crypto")
        System.loadLibrary("ssl")
        System.loadLibrary("curl")
        System.loadLibrary("cwmtransport")
        loaded = true
    }

    @Synchronized
    private fun ensureCaBundle(context: Context): File {
        val output = File(context.cacheDir, "cwm-system-ca.pem")
        if (output.isFile && output.length() > 0L) return output
        output.parentFile?.mkdirs()

        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val temporary = File(output.parentFile, output.name + ".tmp")
        var count = 0
        temporary.bufferedWriter(StandardCharsets.US_ASCII).use { writer ->
            val aliases = store.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (!alias.startsWith("system:")) continue
                val certificate = store.getCertificate(alias) as? X509Certificate ?: continue
                writePem(writer, certificate)
                count += 1
            }
        }
        if (count == 0 || temporary.length() <= 0L) {
            temporary.delete()
            throw IOException("Android 系统 CA 信任库为空")
        }
        if (!temporary.renameTo(output)) temporary.copyTo(output, overwrite = true)
        temporary.delete()
        return output
    }

    private fun writePem(writer: Writer, certificate: X509Certificate) {
        writer.write("-----BEGIN CERTIFICATE-----\n")
        Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)
            .chunked(64)
            .forEach { line ->
                writer.write(line)
                writer.write("\n")
            }
        writer.write("-----END CERTIFICATE-----\n")
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private external fun nativePost(
        path: String,
        userAgent: String,
        caBundlePath: String,
        body: ByteArray,
        connectTimeoutMillis: Int,
        requestTimeoutMillis: Int,
        maximumResponseBytes: Int,
    ): ByteArray
}
