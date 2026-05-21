package com.delivery.app.data.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.delivery.app.utils.CryptoUtil

// ✅ حالات التوصيل الجديدة
enum class DeliveryStatus(val label: String, val emoji: String) {
    PENDING("قيد الانتظار", "⏳"),
    PREPARING("قيد التجهيز", "📦"),
    OUT_FOR_DELIVERY("خرج للتوصيل", "🚚"),
    DELIVERED("تم التوصيل", "✓"),
    RETURNED("مُعاد", "↩️"),
    CANCELLED("ملغي", "✕")
}

@Entity(tableName = "orders")
data class Order(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val orderNumber: Int = 0,  // ← رقم الطلبية
    val customerName: String,
    val customerPhone: String = "",
    val orderType: String,
    val quantity: Int,
    val deliveryAddress: String = "",
    val locationUrl: String = "",
    // ✅ الحقل الجديد — يُخزَّن كـ String (اسم الـ enum)
    val deliveryStatus: String = DeliveryStatus.PENDING.name,
    // ✅ isDelivered محسوب من deliveryStatus للتوافق مع الكود القديم
    val driverName: String,
    val driverId: Long = 0,
    val purchasePrice: Double,
    val deliveryPrice: Double,
    val items: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = SOURCE_MANUAL,
    val notes: String = "",
    val verified: Boolean = false,
    val clientIp: String = "",
    val officeId: Long = 0
) {

    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_WEB = "web"

        fun createEncrypted(
            customerName: String, customerPhone: String, orderType: String,
            quantity: Int, deliveryAddress: String, locationUrl: String,
            deliveryStatus: String, driverName: String, driverId: Long,
            purchasePrice: Double, deliveryPrice: Double, items: String = "[]",
            createdAt: Long = System.currentTimeMillis(), source: String = SOURCE_MANUAL,
            notes: String = "", verified: Boolean = false, clientIp: String = ""
        ): Order = Order(
            customerName = customerName,
            customerPhone = CryptoUtil.encrypt(customerPhone),
            orderType = orderType,
            quantity = quantity,
            deliveryAddress = CryptoUtil.encrypt(deliveryAddress),
            locationUrl = CryptoUtil.encrypt(locationUrl),
            deliveryStatus = deliveryStatus,
            driverName = driverName,
            driverId = driverId,
            purchasePrice = purchasePrice,
            deliveryPrice = deliveryPrice,
            items = items,
            createdAt = createdAt,
            source = source,
            notes = CryptoUtil.encrypt(notes),
            verified = verified,
            clientIp = CryptoUtil.encrypt(clientIp)
        )

        fun encryptPii(order: Order): Order = order.copy(
            customerPhone = CryptoUtil.encrypt(order.customerPhone),
            deliveryAddress = CryptoUtil.encrypt(order.deliveryAddress),
            locationUrl = CryptoUtil.encrypt(order.locationUrl),
            notes = CryptoUtil.encrypt(order.notes),
            clientIp = CryptoUtil.encrypt(order.clientIp)
        )

        fun decryptPii(order: Order): Order = order.copy(
            customerPhone = try { CryptoUtil.decrypt(order.customerPhone) } catch (e: Exception) { order.customerPhone },
            deliveryAddress = try { CryptoUtil.decrypt(order.deliveryAddress) } catch (e: Exception) { order.deliveryAddress },
            locationUrl = try { CryptoUtil.decrypt(order.locationUrl) } catch (e: Exception) { order.locationUrl },
            notes = try { CryptoUtil.decrypt(order.notes) } catch (e: Exception) { order.notes },
            clientIp = try { CryptoUtil.decrypt(order.clientIp) } catch (e: Exception) { order.clientIp }
        )
    }

    val isWebOrder: Boolean
        get() = source == SOURCE_WEB
    // ✅ @Ignore يمنع Room من محاولة قراءتها كأعمدة
    @get:Ignore
    val isDelivered: Boolean
        get() = deliveryStatus == DeliveryStatus.DELIVERED.name

    @get:Ignore
    val statusEnum: DeliveryStatus
        get() = try {
            DeliveryStatus.valueOf(deliveryStatus)
        } catch (e: IllegalArgumentException) {
            DeliveryStatus.PENDING
        }

    @get:Ignore
    val decryptedCustomerPhone: String
        get() = try { CryptoUtil.decrypt(customerPhone) } catch (e: Exception) { "" }

    @get:Ignore
    val decryptedDeliveryAddress: String
        get() = try { CryptoUtil.decrypt(deliveryAddress) } catch (e: Exception) { "" }

    @get:Ignore
    val decryptedLocationUrl: String
        get() = try { CryptoUtil.decrypt(locationUrl) } catch (e: Exception) { "" }

    @get:Ignore
    val decryptedNotes: String
        get() = try { CryptoUtil.decrypt(notes) } catch (e: Exception) { "" }

    @get:Ignore
    val decryptedClientIp: String
        get() = try { CryptoUtil.decrypt(clientIp) } catch (e: Exception) { "" }
}
