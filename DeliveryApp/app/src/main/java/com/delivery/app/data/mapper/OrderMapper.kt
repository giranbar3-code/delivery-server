package com.delivery.app.data.mapper

import com.delivery.app.data.model.DeliveryStatus
import com.delivery.app.data.model.Order
import com.delivery.app.data.remote.RemoteItem
import com.delivery.app.data.remote.RemoteOrder
import com.google.gson.Gson

object OrderMapper {

    private val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    fun remoteToLocal(remote: RemoteOrder, orderNumber: Int = 0, officeId: Long = 0): Order {
        val createdAt = try {
            if (remote.createdAt != null) {
                isoFormat.parse(remote.createdAt)?.time ?: System.currentTimeMillis()
            } else System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }

        val items = remote.items ?: emptyList()
        val itemsJson = if (items.isNotEmpty()) {
            Gson().toJson(items.map { mapOf("name" to it.name, "quantity" to it.quantity) })
        } else "[]"

        return Order(
            orderNumber = orderNumber,
            customerName = remote.customerName ?: "",
            customerPhone = remote.customerPhone ?: "",
            orderType = remote.orderType ?: "",
            quantity = remote.quantity,
            deliveryAddress = remote.deliveryAddress ?: "",
            locationUrl = remote.locationUrl ?: "",
            deliveryStatus = DeliveryStatus.PENDING.name,
            driverName = "",
            purchasePrice = 0.0,
            deliveryPrice = 0.0,
            items = itemsJson,
            createdAt = createdAt,
            source = Order.SOURCE_WEB,
            verified = remote.verified,
            clientIp = remote.clientIp ?: "",
            notes = remote.notes ?: "",
            officeId = officeId
        )
    }

    fun localToRemoteItems(order: Order): List<RemoteItem> {
        return try {
            val jsonArray = org.json.JSONArray(order.items)
            List(jsonArray.length()) { i ->
                val obj = jsonArray.getJSONObject(i)
                RemoteItem(
                    name = obj.optString("name", ""),
                    quantity = obj.optInt("quantity", 1)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun itemsFromJson(json: String): List<Pair<String, Int>> {
        val items = mutableListOf<Pair<String, Int>>()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", "")
                val qty = obj.optInt("quantity", 1)
                if (name.isNotEmpty()) items.add(name to qty)
            }
        } catch (_: Exception) {}
        return items
    }
}
