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

@Component
class DeviceWebSocketHandler(
    private val sessionRegistry: DeviceSessionRegistry,
    private val securityService: SecurityService,
    private val eventDispatcherService: EventDispatcherService
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

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        eventDispatcherService.processIncomingMessage(message.payload, session)
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
