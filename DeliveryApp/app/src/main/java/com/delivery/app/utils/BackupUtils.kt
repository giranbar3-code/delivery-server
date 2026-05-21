package com.delivery.app.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.delivery.app.data.local.DatabaseEncryptionUtil
import com.delivery.app.data.local.DeliveryDatabase
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

object BackupUtils {

    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())

    fun getBackupFileName(): String =
        "delivero_backup_${fileNameFormat.format(Date())}.db"

    fun createBackupFile(context: Context): File? {
        val dbFile = context.getDatabasePath("delivery_database")
        if (!dbFile.exists()) return null
        val tempFile = File(context.cacheDir, getBackupFileName())
        dbFile.copyTo(tempFile, overwrite = true)
        return tempFile
    }

    fun writeBackupToUri(context: Context, uri: Uri): Boolean {
        return try {
            val tempFile = createBackupFile(context) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            }
            tempFile.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun sha256Hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun importBackup(context: Context, backupFile: File, onComplete: (Boolean) -> Unit) {
        try {
            if (!backupFile.exists() || backupFile.length() < 100) {
                onComplete(false)
                return
            }
            val hashBefore = sha256Hash(backupFile)

            synchronized(this) {
                DeliveryDatabase.closeDatabase()
                val dbFile = context.getDatabasePath("delivery_database")
                backupFile.copyTo(dbFile, overwrite = true)
            }

            // التحقق من التكامل: الهاش قبل وبعد الاستيراد
            val restoredFile = context.getDatabasePath("delivery_database")
            val hashAfter = sha256Hash(restoredFile)
            if (hashBefore != hashAfter) {
                restoredFile.delete()
                onComplete(false)
                return
            }

            // التحقق من أن قاعدة البيانات قابلة للفتح باستخدام مفتاح التشفير
            try {
                val passphrase = DatabaseEncryptionUtil.getOrCreatePassphrase(context)
                val passphraseHex = passphrase.joinToString("") { "%02x".format(it) }
                val db = net.sqlcipher.database.SQLiteDatabase.openDatabase(
                    restoredFile.path, passphraseHex, null,
                    net.sqlcipher.database.SQLiteDatabase.OPEN_READONLY
                )
                db.close()
            } catch (e: Exception) {
                restoredFile.delete()
                Log.e("BackupUtils", "فشل التحقق من تشفير الاستيراد", e)
                onComplete(false)
                return
            }

            onComplete(true)
        } catch (e: Exception) {
            Log.e("BackupUtils", "فشل استعادة النسخة الاحتياطية", e)
            onComplete(false)
        }
    }
}
