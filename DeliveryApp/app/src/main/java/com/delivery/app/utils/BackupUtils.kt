package com.delivery.app.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.delivery.app.data.local.DeliveryDatabase
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupUtils {

    private const val KEYSTORE_ALIAS = "delivery_app_backup_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    // تنسيق الملف الجديد (كلمة مرور)
    private val MAGIC_HEADER = byteArrayOf(0x44, 0x4C, 0x56, 0x52) // "DLVR"
    private const val SALT_LENGTH = 16
    private const val PBKDF2_ITERATIONS = 600_000
    private const val NEW_HEADER_LENGTH = 4 + SALT_LENGTH + IV_LENGTH // 4 + 16 + 12 = 32

    private val SQLITE_HEADER = byteArrayOf(0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66, 0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00)

    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())

    // ========== KeyStore (قديم — للتوافق مع النسخ السابقة) ==========

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return kg.generateKey()
    }

    // ========== كلمة مرور (جديد — قابل للنقل بين الأجهزة) ==========

    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun isPasswordEncrypted(data: ByteArray): Boolean {
        if (data.size < NEW_HEADER_LENGTH) return false
        val magic = data.copyOfRange(0, 4)
        return magic.contentEquals(MAGIC_HEADER)
    }

    private fun encryptWithPassword(data: ByteArray, password: String): ByteArray {
        val salt = SecureRandom().generateSeed(SALT_LENGTH)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(data)
        val iv = cipher.iv

        val output = ByteArray(NEW_HEADER_LENGTH + encrypted.size)
        System.arraycopy(MAGIC_HEADER, 0, output, 0, 4)
        System.arraycopy(salt, 0, output, 4, SALT_LENGTH)
        System.arraycopy(iv, 0, output, 4 + SALT_LENGTH, IV_LENGTH)
        System.arraycopy(encrypted, 0, output, NEW_HEADER_LENGTH, encrypted.size)
        return output
    }

    private fun decryptWithPassword(data: ByteArray, password: String): ByteArray {
        val salt = data.copyOfRange(4, 4 + SALT_LENGTH)
        val iv = data.copyOfRange(4 + SALT_LENGTH, NEW_HEADER_LENGTH)
        val ciphertext = data.copyOfRange(NEW_HEADER_LENGTH, data.size)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    // ========== دالة مساعدة للتشفير (اختيار النوع تلقائياً) ==========

    private fun encryptBytes(data: ByteArray, password: String?): ByteArray {
        return if (password != null) {
            encryptWithPassword(data, password)
        } else {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(data)
            val iv = cipher.iv
            val output = ByteArray(IV_LENGTH + encrypted.size)
            System.arraycopy(iv, 0, output, 0, IV_LENGTH)
            System.arraycopy(encrypted, 0, output, IV_LENGTH, encrypted.size)
            output
        }
    }

    private fun decryptBytes(data: ByteArray, password: String?): ByteArray {
        return if (password != null) {
            decryptWithPassword(data, password)
        } else {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = data.copyOfRange(0, IV_LENGTH)
            val ciphertext = data.copyOfRange(IV_LENGTH, data.size)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            cipher.doFinal(ciphertext)
        }
    }

    // ========== الواجهات العامة ==========

    fun getBackupFileName(): String =
        "delivero_backup_${fileNameFormat.format(Date())}.db.enc"

    /**
     * تصدير مع كلمة مرور (password != null) أو باستخدام KeyStore (password == null)
     */
    fun writeBackupToUri(context: Context, uri: Uri, password: String? = null): Boolean {
        return try {
            val tempFile = createBackupFile(context, password) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            }
            tempFile.delete()
            true
        } catch (e: Exception) {
            Log.e("BackupUtils", "فشل حفظ النسخة الاحتياطية", e)
            false
        }
    }

    private fun createBackupFile(context: Context, password: String? = null): File? {
        val dbFile = context.getDatabasePath("delivery_database")
        if (!dbFile.exists()) return null
        val tempFile = File(context.cacheDir, getBackupFileName())
        return try {
            // تبديل journal_mode إلى DELETE يفرغ WAL بالكامل في الملف الرئيسي
            val roomDb = DeliveryDatabase.getDatabase(context)
            if (roomDb.isOpen) {
                val sqliteDb = roomDb.openHelper.writableDatabase
                sqliteDb.query("PRAGMA journal_mode=DELETE").close()
                sqliteDb.query("PRAGMA journal_mode=WAL").close()
            }
            DeliveryDatabase.closeDatabase()
            // فك تشفير PII قبل التصدير (حتى يكون الملف قابلاً للنقل بين الأجهزة)
            decryptPiiInFile(dbFile.absolutePath)
            val dbBytes = dbFile.readBytes()
            val outputBytes = encryptBytes(dbBytes, password)
            tempFile.writeBytes(outputBytes)
            tempFile
        } catch (e: Exception) {
            tempFile.delete()
            Log.e("BackupUtils", "فشل تشفير النسخة الاحتياطية", e)
            null
        }
    }

    private fun decryptPiiInFile(dbPath: String) {
        try {
            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE)
            db.beginTransaction()
            try {
                // Orders
                var cursor = db.rawQuery("SELECT id, customerPhone, deliveryAddress, locationUrl, notes, clientIp FROM orders", null)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val phone = cursor.getString(1)?.let { try { CryptoUtil.decrypt(it) } catch (_: Exception) { it } } ?: ""
                    val addr = cursor.getString(2)?.let { try { CryptoUtil.decrypt(it) } catch (_: Exception) { it } } ?: ""
                    val loc = cursor.getString(3)?.let { try { CryptoUtil.decrypt(it) } catch (_: Exception) { it } } ?: ""
                    val notes = cursor.getString(4)?.let { try { CryptoUtil.decrypt(it) } catch (_: Exception) { it } } ?: ""
                    val ip = cursor.getString(5)?.let { try { CryptoUtil.decrypt(it) } catch (_: Exception) { it } } ?: ""
                    db.execSQL("UPDATE orders SET customerPhone=?, deliveryAddress=?, locationUrl=?, notes=?, clientIp=? WHERE id=?", arrayOf(phone, addr, loc, notes, ip, id))
                }
                cursor.close()
                // Drivers
                cursor = db.rawQuery("SELECT id, phone FROM drivers", null)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val phone = cursor.getString(1)?.let { try { CryptoUtil.decrypt(it) } catch (_: Exception) { it } } ?: ""
                    db.execSQL("UPDATE drivers SET phone=? WHERE id=?", arrayOf(phone, id))
                }
                cursor.close()
                // Customers
                cursor = db.rawQuery("SELECT id, phone, address, notes FROM customers", null)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val phone = cursor.getString(1)?.let { try { CryptoUtil.decrypt(it) } catch (_: Exception) { it } } ?: ""
                    val addr = cursor.getString(2)?.let { try { CryptoUtil.decrypt(it) } catch (_: Exception) { it } } ?: ""
                    val notes = cursor.getString(3)?.let { try { CryptoUtil.decrypt(it) } catch (_: Exception) { it } } ?: ""
                    db.execSQL("UPDATE customers SET phone=?, address=?, notes=? WHERE id=?", arrayOf(phone, addr, notes, id))
                }
                cursor.close()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.close()
            Log.d("BackupUtils", "تم فك تشفير PII قبل التصدير")
        } catch (e: Exception) {
            Log.w("BackupUtils", "تعذر فك تشفير PII (قد لا توجد جداول)", e)
        }
    }

    private fun encryptPiiInFile(dbPath: String) {
        try {
            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE)
            db.beginTransaction()
            try {
                var cursor = db.rawQuery("SELECT id, customerPhone, deliveryAddress, locationUrl, notes, clientIp FROM orders", null)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val phone = CryptoUtil.encrypt(cursor.getString(1) ?: "")
                    val addr = CryptoUtil.encrypt(cursor.getString(2) ?: "")
                    val loc = CryptoUtil.encrypt(cursor.getString(3) ?: "")
                    val notes = CryptoUtil.encrypt(cursor.getString(4) ?: "")
                    val ip = CryptoUtil.encrypt(cursor.getString(5) ?: "")
                    db.execSQL("UPDATE orders SET customerPhone=?, deliveryAddress=?, locationUrl=?, notes=?, clientIp=? WHERE id=?", arrayOf(phone, addr, loc, notes, ip, id))
                }
                cursor.close()
                cursor = db.rawQuery("SELECT id, phone FROM drivers", null)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val phone = CryptoUtil.encrypt(cursor.getString(1) ?: "")
                    db.execSQL("UPDATE drivers SET phone=? WHERE id=?", arrayOf(phone, id))
                }
                cursor.close()
                cursor = db.rawQuery("SELECT id, phone, address, notes FROM customers", null)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val phone = CryptoUtil.encrypt(cursor.getString(1) ?: "")
                    val addr = CryptoUtil.encrypt(cursor.getString(2) ?: "")
                    val notes = CryptoUtil.encrypt(cursor.getString(3) ?: "")
                    db.execSQL("UPDATE customers SET phone=?, address=?, notes=? WHERE id=?", arrayOf(phone, addr, notes, id))
                }
                cursor.close()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.close()
            Log.d("BackupUtils", "تم إعادة تشفير PII بمفتاح الجهاز الجديد")
        } catch (e: Exception) {
            Log.e("BackupUtils", "فشل إعادة تشفير PII", e)
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

    /**
     * استيراد مع كلمة مرور (password != null) أو باستخدام KeyStore (password == null).
     * إذا password == null والملف مشفر بكلمة مرور، تُرجع onComplete(false).
     * إذا password != null والملف من النوع القديم، تُرجع onComplete(false).
     */
    fun importBackup(context: Context, backupFile: File, password: String? = null, onComplete: (Boolean) -> Unit) {
        try {
            if (!backupFile.exists()) {
                onComplete(false)
                return
            }
            val encryptedBytes = backupFile.readBytes()

            // التحقق من التوقيع لمعرفة نوع التشفير
            val isPasswordFile = isPasswordEncrypted(encryptedBytes)
            if (isPasswordFile && password == null) {
                Log.e("BackupUtils", "الملف مشفر بكلمة مرور — يرجى إدخال كلمة المرور")
                onComplete(false)
                return
            }
            if (!isPasswordFile && password != null) {
                Log.e("BackupUtils", "الملف من النوع القديم (KeyStore) — لا يحتاج كلمة مرور")
                onComplete(false)
                return
            }

            val decrypted = decryptBytes(encryptedBytes, password)

            val tempDecrypted = File(context.cacheDir, "backup_decrypted_temp.db")
            tempDecrypted.writeBytes(decrypted)

            if (!isValidDatabase(tempDecrypted)) {
                tempDecrypted.delete()
                onComplete(false)
                return
            }

            synchronized(this) {
                DeliveryDatabase.closeDatabase()
                val dbFile = context.getDatabasePath("delivery_database")
                File(dbFile.parent, "${dbFile.name}-wal").delete()
                File(dbFile.parent, "${dbFile.name}-shm").delete()
                tempDecrypted.copyTo(dbFile, overwrite = true)
                tempDecrypted.delete()
            }
            // إعادة تشفير PII بمفتاح الجهاز الجديد قبل فتح Room
            encryptPiiInFile(context.getDatabasePath("delivery_database").absolutePath)

            // فتح قاعدة البيانات عبر Room (يخلق مفتاح Keystore جديد للجهاز الحالي)
            try {
                DeliveryDatabase.getDatabase(context)
            } catch (e: Exception) {
                Log.e("BackupUtils", "فشل فتح Room بعد الاستيراد", e)
            }

            onComplete(true)
        } catch (e: Exception) {
            Log.e("BackupUtils", "فشل استعادة النسخة الاحتياطية", e)
            onComplete(false)
        }
    }
}