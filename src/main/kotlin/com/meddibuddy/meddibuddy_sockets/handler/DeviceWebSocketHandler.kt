package com.meddibuddy.meddibuddy_sockets.handler

import com.meddibuddy.meddibuddy_sockets.model.ClientType
import com.meddibuddy.meddibuddy_sockets.registry.DeviceSessionRegistry
import com.meddibuddy.meddibuddy_sockets.service.EventDispatcherService
import com.meddibuddy.meddibuddy_sockets.service.SecurityService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.time.LocalTime

@Component
class DeviceWebSocketHandler(
    private val sessionRegistry: DeviceSessionRegistry,
    private val securityService: SecurityService,
    private val eventDispatcherService: EventDispatcherService,
    private val objectMapper: ObjectMapper
) : TextWebSocketHandler() {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val uri = session.uri
        val queryParams = if (uri != null) {
            UriComponentsBuilder.fromUri(uri).build().queryParams
        } else null

        val deviceId = queryParams?.getFirst("deviceId")
        val clientTypeStr = queryParams?.getFirst("clientType") ?: "ESP32"
        val apiKey = queryParams?.getFirst("apiKey") ?: queryParams?.getFirst("token")

        if (!deviceId.isNullOrBlank()) {
            val clientType = try {
                ClientType.valueOf(clientTypeStr.uppercase())
            } catch (e: Exception) {
                ClientType.ESP32
            }

            val isValid = securityService.authenticate(deviceId, clientType, apiKey)
            if (!isValid) {
                logger.warn("Conexión rechazada por credenciales inválidas: deviceId=$deviceId")
                session.close(CloseStatus.NOT_ACCEPTABLE.withReason("UNAUTHORIZED"))
                return
            }

            sessionRegistry.register(session, deviceId, clientType)
            logger.info("Conexión autenticada y registrada vía URL: deviceId=$deviceId, type=$clientType, session=${session.id}")
        } else {
            logger.info("Conexión WebSocket establecida: session=${session.id}. Esperando mensaje CONNECT inicial.")
        }
    }

    /**
     * Interceptor en el flujo WebSocket:
     * Intercepta la carga útil entrante antes del procesamiento general para detectar
     * eventos de hardware en tiempo real (ej. botón físico presionado en el ESP32).
     * Transiciona el estado de la toma a TOMADA o TARDIA según la hora programada.
     */
    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val payload = message.payload

        // Intercepta eventos físicos del dispositivo IoT (ESP32)
        if (isPhysicalButtonEvent(payload)) {
            interceptAndProcessPhysicalButton(session, payload)
        }

        // Delegación al despachador de eventos general
        eventDispatcherService.processIncomingMessage(payload, session)
    }

    /**
     * Evalúa si el payload contiene un indicador de botón físico presionado.
     */
    private fun isPhysicalButtonEvent(rawPayload: String): Boolean {
        return rawPayload.contains("\"buttonPressed\":true") ||
               rawPayload.contains("\"buttonPressed\": true") ||
               rawPayload.contains("\"PHYSICAL_BUTTON_PRESSED\"") ||
               rawPayload.contains("\"BUTTON_PRESSED\"")
    }

    /**
     * Fragmento Interceptor:
     * 1. Extrae deviceId y compartimento del hardware.
     * 2. Obtiene la hora actual.
     * 3. Invoca la lógica POO de transición de estado en MedicationScheduleEntity:
     *    - Si la hora actual <= hora programada + 30 min -> EstadoToma.TOMADA
     *    - Si la hora actual > hora programada + 30 min -> EstadoToma.TARDIA
     */
    fun interceptAndProcessPhysicalButton(session: WebSocketSession, payload: String) {
        try {
            val rootNode = objectMapper.readTree(payload)
            val deviceId = rootNode.get("deviceId")?.asText()
                ?: sessionRegistry.getConnectedDevice(session.id)?.deviceId
                ?: return

            val payloadNode = rootNode.get("payload")
            val compartment = payloadNode?.get("compartment")?.asInt() ?: 1
            val horaActual = LocalTime.now()

            logger.info("🔘 [INTERCEPTOR] Botón físico presionado en pastillero '$deviceId' (compartimento $compartment) a las $horaActual")

            val schedule = eventDispatcherService.procesarEventoBotonFisico(
                deviceId = deviceId,
                compartment = compartment,
                horaActual = horaActual
            )

            if (schedule != null) {
                logger.info("🔘 [INTERCEPTOR] Transición de estado exitosa: Medicamento '${schedule.medicationName}', EstadoToma -> ${schedule.estadoToma}")
            }
        } catch (e: Exception) {
            logger.error("Error al procesar intercepción de botón físico: ${e.message}", e)
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val device = sessionRegistry.getConnectedDevice(session.id)
        if (device != null) {
            securityService.markDeviceOffline(device.deviceId)
        }
        logger.info("Conexión WebSocket cerrada: session=${session.id}, status=$status")
        sessionRegistry.unregister(session.id)
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        val device = sessionRegistry.getConnectedDevice(session.id)
        if (device != null) {
            securityService.markDeviceOffline(device.deviceId)
        }
        logger.error("Error de transporte WebSocket en sesión ${session.id}: ${exception.message}")
        sessionRegistry.unregister(session.id)
    }
}
