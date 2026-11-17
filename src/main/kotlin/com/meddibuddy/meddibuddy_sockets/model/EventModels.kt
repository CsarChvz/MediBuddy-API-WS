package com.meddibuddy.meddibuddy_sockets.model

import com.meddibuddy.meddibuddy_sockets.entity.EstadoToma
import java.time.Instant

enum class MessageType {
    CONNECT,            // Saludo / registro inicial
    TELEMETRY,          // Sensores / telemetría periódica del ESP32
    ALERT,              // Alerta crítica (ej. botón de pánico, dosis omitida)
    COMMAND,            // Comando hacia el dispositivo (ej. dispensar, sonar alarma)
    COMMAND_ACK,        // Confirmación del dispositivo tras ejecutar comando
    SUBSCRIBE,          // App Android suscribe a eventos de un deviceId específico
    UNSUBSCRIBE,        // Cancelar suscripción
    PING,               // Latido (heartbeat)
    PONG,               // Respuesta a latido
    MEDICATION_CONFIG,  // Solicitud de configuración de medicamento
    MEDICATION_DELETE,  // Solicitud de borrado lógico de medicamento
    INTAKE_STATUS       // Reporte o cambio de estado de la toma
}

enum class ClientType {
    ESP32,
    ANDROID,
    WEB,
    SERVER,
    CUIDADOR,
    ADULTO_MAYOR
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

// ============================================================================
// DTOs para comunicación WebSocket y API (Actividad 2: Lógica de Negocio POO)
// ============================================================================

/**
 * Payload para configurar o modificar los parámetros de un horario de medicamento.
 * Regla de negocio: Solo permitido para el rol "CUIDADOR".
 */
data class UpdateMedicationConfigPayload(
    val scheduleId: Long,
    val deviceId: String? = null,
    val medicationName: String,
    val dose: String,
    val scheduledHour: Int,
    val scheduledMinute: Int
)

/**
 * Payload para solicitar el borrado lógico de un medicamento (isActive = false).
 * Regla de negocio: Solo permitido para el rol "CUIDADOR".
 */
data class DeleteMedicationPayload(
    val scheduleId: Long,
    val deviceId: String? = null
)

/**
 * Payload para reportar o transicionar explícitamente el estado de una toma.
 */
data class UpdateTomaStatusPayload(
    val scheduleId: Long,
    val estadoToma: EstadoToma,
    val motivo: String? = null,
    val timestamp: Instant = Instant.now()
)

/**
 * Payload emitido por el hardware IoT (ESP32) cuando se detecta la pulsación física
 * del botón de confirmación de toma en el pastillero.
 */
data class PhysicalButtonPressedPayload(
    val deviceId: String,
    val compartment: Int = 1,
    val pressedAt: Instant = Instant.now()
)

/**
 * DTO estándar de respuesta con el estado del medicamento y la toma.
 */
data class MedicationScheduleResponse(
    val scheduleId: Long?,
    val deviceId: String,
    val medicationName: String,
    val dose: String,
    val scheduledHour: Int,
    val scheduledMinute: Int,
    val compartment: Int,
    val estadoToma: EstadoToma,
    val isActive: Boolean,
    val message: String? = null
)
