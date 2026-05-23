package com.example.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份文件加密：AES-256-GCM + PBKDF2，换机导入时需输入导出时设置的密码。
 *
 * 文件格式: MAGIC(7) + version(1) + salt(16) + iv(12) + ciphertext+tag
 */
object NoteBackupCrypto {

    private val MAGIC = "MINOTE1".encodeToByteArray()
    private const val VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val GCM_TAG_LEN = 128
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LEN = 256

    fun isEncrypted(data: ByteArray): Boolean {
        if (data.size < MAGIC.size + 1 + SALT_LEN + IV_LEN + 16) return false
        return MAGIC.indices.all { data[it] == MAGIC[it] }
    }

    fun encrypt(plainText: ByteArray, password: String): ByteArray {
        require(password.length >= 6) { "备份密码至少 6 位" }
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN, iv))
        val encrypted = cipher.doFinal(plainText)
        return MAGIC + byteArrayOf(VERSION) + salt + iv + encrypted
    }

    fun decrypt(data: ByteArray, password: String): ByteArray {
        require(isEncrypted(data)) { "不是有效的 MiNote 加密备份文件" }
        var offset = MAGIC.size + 1
        val salt = data.copyOfRange(offset, offset + SALT_LEN)
        offset += SALT_LEN
        val iv = data.copyOfRange(offset, offset + IV_LEN)
        offset += IV_LEN
        val ciphertext = data.copyOfRange(offset, data.size)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN, iv))
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw IllegalArgumentException("密码错误或备份文件已损坏", e)
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LEN)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
