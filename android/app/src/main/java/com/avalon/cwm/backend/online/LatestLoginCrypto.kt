package com.avalon.cwm.backend.online

import com.avalon.cwm.backend.online.latest.LatestProtocol
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Official 2.9.365 ParseKsy1 / ParseKsy / GetSMSTask algorithms. */
internal object LatestLoginCrypto {
    // Public encryption key from official assets/android_production.pem.
    private const val PUBLIC_KEY =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxX5AMAGSDhTxsIEahC5t" +
        "Jxypy8qyPijOT2rsMhuUDvENtWpl4axsfLRpD1AlghzBSpNgi1idyZ/OtJFvZsjj" +
        "+drdEO7rCzxMBOlZdw79Gwo06QFSD8JL8X4f49YcGl2+LI5d0KBY2wXdh7urEHQC" +
        "xLK/Lxu9e9ADHXzY26tpCJyvF5LITKZPnzYjGt4fhCEhuoPoeVlJdRAMmGeoRZQ/" +
        "DeRTSAQ1iS3HqalTYRcM4AIiLumivk3vpz8RFsTT0SCKX0zgFRwxkC8pya9/Ls7j" +
        "ALth10rUJTac7fv/801DM6ybAW3IqLgFFUucOwyUF2opRB5AHdoUaa5h4Hb6vwRl" +
        "tQIDAQAB"
    internal val productionKey: PublicKey by lazy {
        KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(decodeBase64(PUBLIC_KEY)),
        )
    }

    internal fun androidBase64(bytes: ByteArray): String {
        val plain = encodeBase64(bytes)
        return if (plain.isEmpty()) "" else plain.chunked(76).joinToString("\n", postfix = "\n")
    }

    internal fun encrypt(value: String, publicKey: PublicKey = productionKey): String {
        val input = value.toByteArray(Charsets.UTF_8)
        val maxBytes = (((publicKey as RSAPublicKey).modulus.bitLength() + 7) / 8) - 11
        require(input.size <= maxBytes) { "账号或密码长度超过服务器加密上限" }
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return androidBase64(cipher.doFinal(input))
    }

    fun passwordFields(loginName: String, password: String, publicKey: PublicKey = productionKey): Map<String, String> = mapOf(
        "passwd" to encrypt(password, publicKey),
        "sign" to encrypt(loginName + "_" + password, publicKey),
    )

    fun verificationHash(account: String, timestamp: Long): String {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(LatestProtocol.RESPONSE_SEED.toByteArray(Charsets.UTF_8))
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        val encoded = androidBase64(aes.doFinal((account + timestamp).toByteArray(Charsets.UTF_8))).trim()
        return MessageDigest.getInstance("MD5").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    // Pure-Kotlin RFC 4648 base64. Output stays byte-identical with
    // android.util.Base64 (NO_WRAP for encoding, DEFAULT for decoding) while
    // remaining callable from JVM unit tests, where android.* classes are
    // unresolved stubs that throw "not mocked".
    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun encodeBase64(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index + 3 <= bytes.size) {
            val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8) or
                (bytes[index + 2].toInt() and 0xFF)
            out.append(BASE64_ALPHABET[chunk ushr 18 and 0x3F])
            out.append(BASE64_ALPHABET[chunk ushr 12 and 0x3F])
            out.append(BASE64_ALPHABET[chunk ushr 6 and 0x3F])
            out.append(BASE64_ALPHABET[chunk and 0x3F])
            index += 3
        }
        when (bytes.size - index) {
            1 -> {
                val chunk = (bytes[index].toInt() and 0xFF) shl 16
                out.append(BASE64_ALPHABET[chunk ushr 18 and 0x3F])
                out.append(BASE64_ALPHABET[chunk ushr 12 and 0x3F])
                out.append("==")
            }
            2 -> {
                val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                    ((bytes[index + 1].toInt() and 0xFF) shl 8)
                out.append(BASE64_ALPHABET[chunk ushr 18 and 0x3F])
                out.append(BASE64_ALPHABET[chunk ushr 12 and 0x3F])
                out.append(BASE64_ALPHABET[chunk ushr 6 and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
    }

    private fun decodeBase64(value: String): ByteArray {
        val out = ByteArrayOutputStream(value.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (raw in value) {
            if (raw == '=') break
            if (raw == '\n' || raw == '\r' || raw == ' ' || raw == '\t') continue
            val digit = BASE64_ALPHABET.indexOf(raw)
            require(digit >= 0) { "非法 Base64 字符" }
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
