package com.avalon.cwm.backend.core

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CiweimaoCrypto {
    private const val BLOCK_SIZE = 16
    private val zeroIv = IvParameterSpec(ByteArray(BLOCK_SIZE))

    /** Equivalent to src/decrypt.py: AES-CBC plus strict PKCS#7 validation. */
    fun decryptChapter(ciphertextBase64: String, seed: String): String {
        val decrypted = decryptRaw(ciphertextBase64, seed)
        return strictPkcs7Unpad(decrypted).toString(StandardCharsets.UTF_8)
    }

    /**
     * Equivalent to online_api.py. Its legacy unpad helper deliberately leaves bytes unchanged
     * when the trailing byte is not valid PKCS#7 padding, so protocol errors remain classifiable.
     */
    fun decryptOnlineResponse(ciphertextBase64: String, seed: String): ByteArray {
        val decrypted = decryptRaw(ciphertextBase64, seed)
        if (decrypted.isEmpty()) return decrypted
        val padding = decrypted.last().toInt() and 0xff
        return if (padding in 1..BLOCK_SIZE && padding <= decrypted.size) {
            decrypted.copyOf(decrypted.size - padding)
        } else {
            decrypted
        }
    }

    private fun decryptRaw(ciphertextBase64: String, seed: String): ByteArray {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(StandardCharsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), zeroIv)
        val ciphertext = Base64.decode(ciphertextBase64, Base64.DEFAULT)
        require(ciphertext.isNotEmpty() && ciphertext.size % BLOCK_SIZE == 0) {
            "AES 密文长度无效"
        }
        return cipher.doFinal(ciphertext)
    }

    private fun strictPkcs7Unpad(data: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "PKCS#7 明文为空" }
        val padding = data.last().toInt() and 0xff
        require(padding in 1..BLOCK_SIZE && padding <= data.size) { "PKCS#7 填充无效" }
        for (index in data.size - padding until data.size) {
            require((data[index].toInt() and 0xff) == padding) { "PKCS#7 填充无效" }
        }
        return data.copyOf(data.size - padding)
    }
}
