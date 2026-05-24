package com.delivery.app.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
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

data class RemoteDriver(
    val id: Long = 0,
    val name: String = "",
    val phone: String = "",
    val office_id: Long = 0
)

data class CreateDriverRequest(
    val name: String,
    val phone: String,
    val pin: String,
    val officeId: Long = 0
)

data class CreateDriverResponse(
    val driver: RemoteDriver? = null,
    val error: String? = null
)

interface OrderApi {

    @GET("api/orders")
    suspend fun getNewOrders(@Query("since") since: String? = null, @Query("officeId") officeId: Long = 0): List<RemoteOrder>

    @POST("api/orders/sync")
    suspend fun markSynced(@Body request: SyncRequest): Map<String, Any>

    // ══════ points API للسائقين ══════

    @GET("api/drivers")
    suspend fun getRemoteDrivers(@Query("officeId") officeId: Long = 0): List<RemoteDriver>

    @POST("api/drivers")
    suspend fun createRemoteDriver(@Body request: CreateDriverRequest): CreateDriverResponse

    @PUT("api/drivers/{id}")
    suspend fun updateRemoteDriver(@Path("id") id: Long, @Body request: CreateDriverRequest): CreateDriverResponse

    @DELETE("api/drivers/{id}")
    suspend fun deleteRemoteDriver(@Path("id") id: Long): Map<String, Any>

    @PUT("api/orders/{id}/driver")
    suspend fun assignDriverToOrder(
        @Path("id") orderId: Long,
        @Body body: Map<String, String>
    ): Map<String, Any>

    @PUT("api/orders/{id}/status")
    suspend fun updateOrderStatus(
        @Path("id") orderId: Long,
        @Body body: Map<String, String>
    ): Map<String, Any>
}
