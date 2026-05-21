package com.delivery.app.data.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.delivery.app.utils.CryptoUtil

@Entity(tableName = "drivers")
data class Driver(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val phone: String = "",
    val officeId: Long = 0
) {
    companion object {
        fun createEncrypted(name: String, phone: String = ""): Driver = Driver(
            name = name,
            phone = CryptoUtil.encrypt(phone)
        )

        fun encryptPii(d: Driver): Driver = d.copy(
            phone = CryptoUtil.encrypt(d.phone)
        )

        fun decryptPii(d: Driver): Driver = d.copy(
            phone = try { CryptoUtil.decrypt(d.phone) } catch (e: Exception) { d.phone }
        )
    }

    @get:Ignore
    val decryptedPhone: String
        get() = try { CryptoUtil.decrypt(phone) } catch (e: Exception) { "" }
}