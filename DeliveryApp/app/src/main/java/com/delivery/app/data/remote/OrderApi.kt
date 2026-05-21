package com.delivery.app.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

data class RemoteOrder(
    val id: Long,
    val customerName: String?,
    val customerPhone: String?,
    val orderType: String?,
    val quantity: Int = 0,
    val deliveryAddress: String?,
    val locationUrl: String? = "",
    val notes: String? = "",
    val items: List<RemoteItem>? = emptyList(),
    val createdAt: String?,
    val synced: Boolean = false,
    val verified: Boolean = false,
    val clientIp: String? = ""
)

data class RemoteItem(
    val name: String = "",
    val quantity: Int = 1
)

data class SyncRequest(val ids: List<Long>)

interface OrderApi {

    @GET("api/orders")
    suspend fun getNewOrders(@Query("since") since: String? = null, @Query("officeId") officeId: Long = 0): List<RemoteOrder>

    @POST("api/orders/sync")
    suspend fun markSynced(@Body request: SyncRequest): Map<String, Any>
}
