package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.security.DatabaseKeyManager
import kotlinx.coroutines.runBlocking
import net.sqlcipher.database.SupportFactory
import androidx.core.content.edit

@Database(entities = [NoteEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        private const val LEGACY_DB_NAME = "minotes_database"
        private const val ENCRYPTED_DB_NAME = "minotes_database_enc"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        @Volatile
        private var sqlCipherLoaded = false

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = INSTANCE ?: buildEncryptedDatabase(context.applicationContext).also {
                    INSTANCE = it
                }
                instance
            }
        }

        private fun ensureSqlCipherLoaded() {
            if (!sqlCipherLoaded) {
                System.loadLibrary("sqlcipher")
                sqlCipherLoaded = true
            }
        }

        private fun buildEncryptedDatabase(context: Context): AppDatabase {
            ensureSqlCipherLoaded()
            val legacyNotes = extractAndRemoveLegacyDatabase(context)
            val passphrase = DatabaseKeyManager.getDatabasePassphrase(context)
            val factory = SupportFactory(passphrase)
            val db = Room.databaseBuilder(context, AppDatabase::class.java, ENCRYPTED_DB_NAME)
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
            if (legacyNotes.isNotEmpty()) {
                runBlocking {
                    db.noteDao().insertNotes(legacyNotes)
                }
            }
            return db
        }

        /** 将旧版未加密数据库中的便签读出并删除明文库文件 */
        private fun extractAndRemoveLegacyDatabase(context: Context): List<NoteEntity> {
            val prefs = context.getSharedPreferences("mi_db_state", Context.MODE_PRIVATE)
            if (prefs.getBoolean("legacy_migrated", false)) {
                return emptyList()
            }
            if (!context.getDatabasePath(LEGACY_DB_NAME).exists()) {
                prefs.edit { putBoolean("legacy_migrated", true) }
                return emptyList()
            }
            return try {
                val legacyDb = Room.databaseBuilder(context, AppDatabase::class.java, LEGACY_DB_NAME)
                    .allowMainThreadQueries()
                    .build()
                val notes = runBlocking { legacyDb.noteDao().getAllNotesSnapshot() }
                legacyDb.close()
                context.deleteDatabase(LEGACY_DB_NAME)
                prefs.edit { putBoolean("legacy_migrated", true) }
                notes
            } catch (e: Exception) {
                context.deleteDatabase(LEGACY_DB_NAME)
                prefs.edit { putBoolean("legacy_migrated", true) }
                emptyList()
            }
        }
    }
}
