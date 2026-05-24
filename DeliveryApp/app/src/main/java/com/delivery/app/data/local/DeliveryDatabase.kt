package com.delivery.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.delivery.app.data.model.Customer
import com.delivery.app.data.model.Driver
import com.delivery.app.data.model.ErrorLog
import com.delivery.app.data.model.Office
import com.delivery.app.data.model.Order
import com.delivery.app.data.model.StatusHistory
import com.delivery.app.data.local.OfficeDao

@Database(entities = [Order::class, Driver::class, Customer::class, Office::class, ErrorLog::class, StatusHistory::class], version = 14, exportSchema = true)
abstract class DeliveryDatabase : RoomDatabase() {

    abstract fun orderDao(): OrderDao
    abstract fun driverDao(): DriverDao
    abstract fun customerDao(): CustomerDao
    abstract fun officeDao(): OfficeDao
    abstract fun errorLogDao(): ErrorLogDao
    abstract fun statusHistoryDao(): StatusHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: DeliveryDatabase? = null

        @Synchronized
        fun closeDatabase() {
            INSTANCE?.let {
                if (it.isOpen) {
                    it.close()
                }
            }
            INSTANCE = null
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `drivers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `phone` TEXT NOT NULL DEFAULT '')")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE orders ADD COLUMN customerPhone TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE orders ADD COLUMN locationUrl TEXT NOT NULL DEFAULT ''")
            }
        }

        // ✅ Migration 3→4: إضافة حقل deliveryStatus
        // الطلبيات القديمة: isDelivered=1 → DELIVERED، isDelivered=0 → PENDING
        // ملاحظة: isDelivered لم يعد عموداً في قاعدة البيانات — أصبح خاصية مشتقة
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE orders ADD COLUMN deliveryStatus TEXT NOT NULL DEFAULT 'PENDING'")
                try {
                    db.execSQL("UPDATE orders SET deliveryStatus = 'DELIVERED' WHERE isDelivered = 1")
                } catch (e: Exception) {
                    // عمود isDelivered غير موجود في بعض النسخ القديمة، تجاهل الخطأ
                }
            }
        }

	private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE orders ADD COLUMN orderNumber INTEGER NOT NULL DEFAULT 0")
    	}
		}

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE orders ADD COLUMN items TEXT NOT NULL DEFAULT '[]'")
        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE orders ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE orders ADD COLUMN driverId INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE IF NOT EXISTS `customers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `phone` TEXT NOT NULL, `address` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE orders ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE orders ADD COLUMN verified INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE orders ADD COLUMN clientIp TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `username` TEXT NOT NULL, `passwordHash` TEXT NOT NULL, `salt` TEXT NOT NULL, `role` TEXT NOT NULL, `officeId` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL)")
        }
    }

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `offices` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `phone` TEXT NOT NULL DEFAULT '', `address` TEXT NOT NULL DEFAULT '')")
            db.execSQL("ALTER TABLE orders ADD COLUMN officeId INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE customers ADD COLUMN officeId INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE drivers ADD COLUMN officeId INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `error_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `level` TEXT NOT NULL, `tag` TEXT NOT NULL, `message` TEXT NOT NULL, `stackTrace` TEXT, `timestamp` INTEGER NOT NULL)")
        }
    }

    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `status_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `orderId` INTEGER NOT NULL, `fromStatus` TEXT NOT NULL, `toStatus` TEXT NOT NULL, `changedAt` INTEGER NOT NULL)")
        }
    }

        fun getDatabase(context: Context): DeliveryDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DeliveryDatabase::class.java,
                    "delivery_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
