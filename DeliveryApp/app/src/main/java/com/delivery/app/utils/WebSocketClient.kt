package com.delivery.app.utils

import android.content.Context
import android.util.Log
import com.delivery.app.data.OfficeManager
import com.delivery.app.data.remote.RetrofitClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketClient(private val context: Context) {

    private var webSocket: WebSocket? = null
    private var shouldReconnect = true
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun connect() {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "https://delivery-server-mmdt.onrender.com/")
            ?.trimEnd('/') ?: return
        val wsUrl = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        val officeId = OfficeManager.currentOfficeId.value ?: 0L
        val request = Request.Builder()
            .url("$wsUrl?officeId=$officeId")
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i("WSC", "WebSocket متصل")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "new_order") {
                        val count = json.optJSONObject("order")?.let { 1 } ?: 1
                        NotificationHelper.notifyNewOrders(context, count)
                    }
                } catch (e: Exception) {
                    Log.w("WSC", "فشل تحليل رسالة WebSocket", e)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i("WSC", "WebSocket مغلق: $code $reason")
                if (shouldReconnect) reconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w("WSC", "فشل WebSocket", t)
                if (shouldReconnect) reconnect()
            }
        })
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "إغلاق")
        webSocket = null
    }

    private fun reconnect() {
        if (!shouldReconnect) return
        webSocket = null
        android.os.Handler(context.mainLooper).postDelayed({ connect() }, 5000)
    }
}
