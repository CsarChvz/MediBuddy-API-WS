package com.meddibuddy.android.websocket

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * Gestor WebSocket en Kotlin para la App Android (usando OkHttp).
 * Dependencias Gradle para Android:
 *   implementation("com.squareup.okhttp3:okhttp:4.12.0")
 *   implementation("com.google.code.gson:gson:2.10.1")
 *   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
 */

data class DeviceMessage(
    val type: String,
    val deviceId: String,
    val clientType: String = "ANDROID",
    val targetDeviceId: String? = null,
    val payload: Map<String, Any?> = emptyMap()
)

class AndroidWebSocketManager(
    private val serverUrl: String = "ws://10.0.2.2:8080/ws/events", // 10.0.2.2 para emulador Android
    private val appUserId: String = "USER_ANDROID_MOBILE",
    private val targetEsp32Id: String = "ESP32_MED_01"
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Mantener conexión viva
        .pingInterval(20, TimeUnit.SECONDS)   // Ping automático para evitar cierre
        .build()

    private val gson = Gson()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var webSocket: WebSocket? = null

    // Flujo reactivo de eventos entrantes para observar en ViewModel / Jetpack Compose
    private val _eventsFlow = MutableSharedFlow<DeviceMessage>(extraBufferCapacity = 64)
    val eventsFlow: SharedFlow<DeviceMessage> = _eventsFlow.asSharedFlow()

    fun connect() {
        val urlWithParams = "$serverUrl?deviceId=$appUserId&clientType=ANDROID"
        val request = Request.Builder().url(urlWithParams).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                println("✅ WebSocket Conectado desde Android")

                // Suscribirse a las alertas y telemetría del ESP32
                val subscribeMsg = DeviceMessage(
                    type = "SUBSCRIBE",
                    deviceId = appUserId,
                    targetDeviceId = targetEsp32Id
                )
                ws.send(gson.toJson(subscribeMsg))
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val message = gson.fromJson(text, DeviceMessage::class.java)
                    coroutineScope.launch {
                        _eventsFlow.emit(message)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                println("❌ Error WebSocket: ${t.message}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                println("WebSocket cerrado: $reason")
            }
        })
    }

    /**
     * Enviar un comando directo hacia el ESP32 desde la interfaz de usuario
     */
    fun sendCommandToEsp32(action: String, params: Map<String, Any?> = emptyMap()) {
        val command = DeviceMessage(
            type = "COMMAND",
            deviceId = appUserId,
            targetDeviceId = targetEsp32Id,
            payload = mapOf("action" to action) + params
        )
        webSocket?.send(gson.toJson(command))
    }

    fun disconnect() {
        webSocket?.close(1000, "App cerrada")
    }
}
