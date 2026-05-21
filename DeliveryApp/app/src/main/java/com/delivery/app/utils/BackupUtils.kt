package com.delivery.app.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.delivery.app.data.local.DeliveryDatabase
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*

object BackupUtils {

    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
    private val SQLITE_HEADER = byteArrayOf(0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66, 0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00)

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

    private fun isValidDatabase(file: File): Boolean {
        return try {
            if (!file.exists() || file.length() < 100) return false
            val header = ByteArray(16)
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(header)
            }
            header.contentEquals(SQLITE_HEADER)
        } catch (e: Exception) {
            false
        }
    }

    fun importBackup(context: Context, backupFile: File, onComplete: (Boolean) -> Unit) {
        try {
            if (!isValidDatabase(backupFile)) {
                onComplete(false)
                return
            }
            synchronized(this) {
                DeliveryDatabase.closeDatabase()
                val dbFile = context.getDatabasePath("delivery_database")
                backupFile.copyTo(dbFile, overwrite = true)
            }
            onComplete(true)
        } catch (e: Exception) {
            Log.e("BackupUtils", "فشل استعادة النسخة الاحتياطية", e)
            onComplete(false)
        }
    }
}
