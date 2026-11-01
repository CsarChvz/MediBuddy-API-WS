package com.meddibuddy.meddibuddy_sockets.model

import java.time.Instant

enum class MessageType {
    CONNECT,        // Saludo / registro inicial
    TELEMETRY,      // Sensores / telemetría periódica del ESP32
    ALERT,          // Alerta crítica (ej. botón de pánico, dosis omitida)
    COMMAND,        // Comando hacia el dispositivo (ej. dispensar, sonar alarma)
    COMMAND_ACK,    // Confirmación del dispositivo tras ejecutar comando
    SUBSCRIBE,      // App Android suscribe a eventos de un deviceId específico
    UNSUBSCRIBE,    // Cancelar suscripción
    PING,           // Latido (heartbeat)
    PONG            // Respuesta a latido
}

enum class ClientType {
    ESP32,
    ANDROID,
    WEB,
    SERVER
}

data class DeviceMessage(
    val type: MessageType,
    val deviceId: String,
    val clientType: ClientType = ClientType.ESP32,
    val targetDeviceId: String? = null,
    val timestamp: Instant = Instant.now(),
    val payload: Map<String, Any?> = emptyMap(),
    val messageId: String? = null
)

data class ConnectedDevice(
    val deviceId: String,
    val clientType: ClientType,
    val sessionId: String,
    val remoteAddress: String?,
    val connectedAt: Instant = Instant.now(),
    var lastSeen: Instant = Instant.now(),
    val metadata: MutableMap<String, Any?> = mutableMapOf()
)
