package com.example.security

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * 设备级数据库密钥：保存在 EncryptedSharedPreferences，用于 SQLCipher 加密 Room 数据库。
 */
object DatabaseKeyManager {

    private const val PREFS_NAME = "mi_secure_db_prefs"
    private const val KEY_PASSPHRASE = "db_passphrase_b64"

    fun getDatabasePassphrase(context: Context): ByteArray {
        val prefs = encryptedPrefs(context)
        var encoded = prefs.getString(KEY_PASSPHRASE, null)
        if (encoded == null) {
            val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
            encoded = Base64.encodeToString(random, Base64.NO_WRAP)
            prefs.edit().putString(KEY_PASSPHRASE, encoded).apply()
        }
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    private fun encryptedPrefs(context: Context) =
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
}
