package com.delivery.app.data.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.delivery.app.utils.CryptoUtil

@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val phone: String = "",
    val address: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val officeId: Long = 0
) {
    companion object {
        fun createEncrypted(name: String, phone: String = "", address: String = "",
                            notes: String = "", createdAt: Long = System.currentTimeMillis()
        ): Customer = Customer(
            name = name,
            phone = CryptoUtil.encrypt(phone),
            address = CryptoUtil.encrypt(address),
            notes = CryptoUtil.encrypt(notes),
            createdAt = createdAt
        )

        fun encryptPii(c: Customer): Customer = c.copy(
            phone = CryptoUtil.encrypt(c.phone),
            address = CryptoUtil.encrypt(c.address),
            notes = CryptoUtil.encrypt(c.notes)
        )

        fun decryptPii(c: Customer): Customer = c.copy(
            phone = try { CryptoUtil.decrypt(c.phone) } catch (e: Exception) { c.phone },
            address = try { CryptoUtil.decrypt(c.address) } catch (e: Exception) { c.address },
            notes = try { CryptoUtil.decrypt(c.notes) } catch (e: Exception) { c.notes }
        )
    }

    @get:Ignore
    val decryptedPhone: String
        get() = try { CryptoUtil.decrypt(phone) } catch (e: Exception) { "" }

    @get:Ignore
    val decryptedAddress: String
        get() = try { CryptoUtil.decrypt(address) } catch (e: Exception) { "" }

    @get:Ignore
    val decryptedNotes: String
        get() = try { CryptoUtil.decrypt(notes) } catch (e: Exception) { "" }
}
