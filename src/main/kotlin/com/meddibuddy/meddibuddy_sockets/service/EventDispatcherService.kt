package com.meddibuddy.meddibuddy_sockets.service

import com.meddibuddy.meddibuddy_sockets.entity.DeviceCommandEntity
import com.meddibuddy.meddibuddy_sockets.entity.DeviceEventEntity
import com.meddibuddy.meddibuddy_sockets.model.ClientType
import com.meddibuddy.meddibuddy_sockets.model.DeviceMessage
import com.meddibuddy.meddibuddy_sockets.model.MessageType
import com.meddibuddy.meddibuddy_sockets.registry.DeviceSessionRegistry
import com.meddibuddy.meddibuddy_sockets.repository.DeviceCommandRepository
import com.meddibuddy.meddibuddy_sockets.repository.DeviceEventRepository
import com.meddibuddy.meddibuddy_sockets.repository.MedicationScheduleRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

@Service
class EventDispatcherService(
    private val sessionRegistry: DeviceSessionRegistry,
    private val securityService: SecurityService,
    private val eventRepository: DeviceEventRepository,
    private val commandRepository: DeviceCommandRepository,
    private val scheduleRepository: MedicationScheduleRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val maxHistorySize = 200
    private val eventHistory = ConcurrentLinkedDeque<DeviceMessage>()

    fun processIncomingMessage(rawPayload: String, session: WebSocketSession) {
        val message = try {
            objectMapper.readValue(rawPayload, DeviceMessage::class.java)
        } catch (e: Exception) {
            logger.error("Error al deserializar mensaje WebSocket desde sesión ${session.id}: ${e.message}")
            sendJson(session, DeviceMessage(
                type = MessageType.ALERT,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                payload = mapOf("error" to "JSON inválido: ${e.message}")
            ))
            return
        }

        sessionRegistry.updateLastSeen(session.id)

        when (message.type) {
            MessageType.CONNECT -> handleConnect(message, session)
            MessageType.SUBSCRIBE -> handleSubscribe(message, session)
            MessageType.UNSUBSCRIBE -> handleUnsubscribe(message, session)
            MessageType.PING -> handlePing(session, message)
            MessageType.PONG -> logger.debug("PONG recibido de ${message.deviceId}")
            MessageType.TELEMETRY -> handleTelemetry(message)
            MessageType.ALERT -> handleAlert(message)
            MessageType.COMMAND -> handleCommand(message, session)
            MessageType.COMMAND_ACK -> handleCommandAck(message)
        }
    }

    private fun handleConnect(message: DeviceMessage, session: WebSocketSession) {
        val apiKeyOrToken = message.payload["apiKey"]?.toString()
            ?: message.payload["token"]?.toString()

        val authenticated = securityService.authenticate(message.deviceId, message.clientType, apiKeyOrToken)
        if (!authenticated) {
            logger.warn("Autenticación fallida para deviceId=${message.deviceId}")
            sendJson(session, DeviceMessage(
                type = MessageType.ALERT,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                payload = mapOf("error" to "UNAUTHORIZED: Clave o token inválido")
            ))
            session.close()
            return
        }

        sessionRegistry.register(
            session = session,
            deviceId = message.deviceId,
            clientType = message.clientType,
            metadata = message.payload
        )

        sendJson(session, DeviceMessage(
            type = MessageType.COMMAND_ACK,
            deviceId = "SERVER",
            clientType = ClientType.SERVER,
            targetDeviceId = message.deviceId,
            payload = mapOf(
                "status" to "CONNECTED",
                "sessionId" to session.id,
                "authenticated" to true,
                "timestamp" to Instant.now().toString()
            )
        ))
    }

    private fun handleSubscribe(message: DeviceMessage, session: WebSocketSession) {
        val target = message.targetDeviceId ?: message.payload["targetDeviceId"]?.toString() ?: "*"
        sessionRegistry.subscribeToDevice(session.id, target)

        // Enviar horarios activos de medicamentos si el objetivo es un ESP32
        val schedules = if (target != "*") scheduleRepository.findByDeviceIdAndIsActiveTrue(target) else emptyList()

        sendJson(session, DeviceMessage(
            type = MessageType.COMMAND_ACK,
            deviceId = "SERVER",
            clientType = ClientType.SERVER,
            targetDeviceId = target,
            payload = mapOf(
                "status" to "SUBSCRIBED",
                "target" to target,
                "activeSchedulesCount" to schedules.size
            )
        ))
    }

    private fun handleUnsubscribe(message: DeviceMessage, session: WebSocketSession) {
        val target = message.targetDeviceId ?: message.payload["targetDeviceId"]?.toString() ?: "*"
        sessionRegistry.unsubscribeFromDevice(session.id, target)
    }

    private fun handlePing(session: WebSocketSession, message: DeviceMessage) {
        sendJson(session, DeviceMessage(
            type = MessageType.PONG,
            deviceId = "SERVER",
            clientType = ClientType.SERVER,
            targetDeviceId = message.deviceId,
            payload = mapOf("timestamp" to Instant.now().toString())
        ))
    }

    private fun handleTelemetry(message: DeviceMessage) {
        logger.info("📡 Telemetría recibida de ESP32 [${message.deviceId}]: ${message.payload}")
        recordEvent(message)
        persistEvent(message)

        // Lógica de Negocio MediBuddy:
        // 1. Detección de batería baja
        val battery = (message.payload["battery"] as? Number)?.toInt()
        if (battery != null && battery < 20) {
            val alert = DeviceMessage(
                type = MessageType.ALERT,
                deviceId = message.deviceId,
                clientType = ClientType.SERVER,
                payload = mapOf(
                    "alertType" to "LOW_BATTERY",
                    "batteryLevel" to battery,
                    "message" to "La batería del pastillero ${message.deviceId} está baja ($battery%)"
                )
            )
            recordEvent(alert)
            persistEvent(alert)
            forwardToSubscribers(alert)
        }

        // 2. Detección de toma de medicamento (pastillero abierto)
        val boxOpened = message.payload["pillBoxClosed"] == false || message.payload["pillBoxOpened"] == true
        if (boxOpened) {
            val adherenceAlert = DeviceMessage(
                type = MessageType.ALERT,
                deviceId = message.deviceId,
                clientType = ClientType.ESP32,
                payload = mapOf(
                    "alertType" to "MEDICATION_TAKEN",
                    "compartment" to (message.payload["compartment"] ?: 1),
                    "message" to "Compartimento abierto: Toma de medicamento detectada"
                )
            )
            recordEvent(adherenceAlert)
            persistEvent(adherenceAlert)
            forwardToSubscribers(adherenceAlert)
        }

        forwardToSubscribers(message)
    }

    private fun handleAlert(message: DeviceMessage) {
        logger.warn("🚨 ALERTA recibida de dispositivo [${message.deviceId}]: ${message.payload}")
        recordEvent(message)
        persistEvent(message)
        forwardToSubscribers(message)
    }

    private fun handleCommand(message: DeviceMessage, senderSession: WebSocketSession) {
        val targetDevice = message.targetDeviceId
        if (targetDevice.isNullOrBlank()) {
            sendJson(senderSession, DeviceMessage(
                type = MessageType.ALERT,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                payload = mapOf("error" to "Debe especificar targetDeviceId para enviar un comando")
            ))
            return
        }

        val action = message.payload["action"]?.toString() ?: "UNKNOWN"
        val paramsJson = try { objectMapper.writeValueAsString(message.payload) } catch (_: Exception) { "{}" }

        // Persistir comando en DB
        val commandEntity = commandRepository.save(
            DeviceCommandEntity(
                targetDeviceId = targetDevice,
                issuedBy = message.deviceId,
                action = action,
                paramsJson = paramsJson,
                status = "PENDING"
            )
        )

        val espSession = sessionRegistry.getEsp32Session(targetDevice)
        if (espSession != null && espSession.isOpen) {
            logger.info("➡️ Reenviando comando desde [${message.deviceId}] hacia ESP32 [$targetDevice]: ${message.payload}")
            commandEntity.status = "SENT"
            commandRepository.save(commandEntity)
            sendJson(espSession, message)
        } else {
            logger.warn("No se puede enviar comando: ESP32 [$targetDevice] no está conectado")
            commandEntity.status = "FAILED_OFFLINE"
            commandRepository.save(commandEntity)

            sendJson(senderSession, DeviceMessage(
                type = MessageType.ALERT,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                targetDeviceId = targetDevice,
                payload = mapOf("error" to "Dispositivo destino no conectado: $targetDevice")
            ))
        }
    }

    private fun handleCommandAck(message: DeviceMessage) {
        logger.info("✅ ACK de comando desde ESP32 [${message.deviceId}]: ${message.payload}")
        recordEvent(message)
        persistEvent(message)

        val action = message.payload["action"]?.toString()
            ?: message.payload["executedAction"]?.toString()
            ?: ""

        // Actualizar estado en DB
        val pendingCommand = commandRepository.findFirstByTargetDeviceIdAndActionAndStatusOrderByCreatedAtDesc(
            targetDeviceId = message.deviceId,
            action = action,
            status = "SENT"
        )
        if (pendingCommand != null) {
            pendingCommand.status = "EXECUTED"
            pendingCommand.executedAt = Instant.now()
            commandRepository.save(pendingCommand)
        }

        forwardToSubscribers(message)
    }

    fun sendDirectCommand(targetDeviceId: String, action: String, params: Map<String, Any?> = emptyMap()): Boolean {
        val espSession = sessionRegistry.getEsp32Session(targetDeviceId)
        if (espSession == null || !espSession.isOpen) {
            return false
        }

        val paramsJson = try { objectMapper.writeValueAsString(params) } catch (_: Exception) { "{}" }
        commandRepository.save(
            DeviceCommandEntity(
                targetDeviceId = targetDeviceId,
                issuedBy = "API_REST",
                action = action,
                paramsJson = paramsJson,
                status = "SENT"
            )
        )

        val message = DeviceMessage(
            type = MessageType.COMMAND,
            deviceId = "API_SERVER",
            clientType = ClientType.SERVER,
            targetDeviceId = targetDeviceId,
            payload = mapOf("action" to action) + params
        )

        sendJson(espSession, message)
        return true
    }

    private fun forwardToSubscribers(message: DeviceMessage) {
        val subscribers = sessionRegistry.getSubscribersForDevice(message.deviceId)
        if (subscribers.isEmpty()) {
            return
        }

        for (subSession in subscribers) {
            sendJson(subSession, message)
        }
    }

    private fun recordEvent(message: DeviceMessage) {
        eventHistory.addFirst(message)
        while (eventHistory.size > maxHistorySize) {
            eventHistory.pollLast()
        }
    }

    private fun persistEvent(message: DeviceMessage) {
        try {
            val json = objectMapper.writeValueAsString(message.payload)
            eventRepository.save(
                DeviceEventEntity(
                    deviceId = message.deviceId,
                    eventType = message.type.name,
                    payloadJson = json,
                    timestamp = message.timestamp
                )
            )
        } catch (e: Exception) {
            logger.error("Error al persistir evento en BD: ${e.message}")
        }
    }

    fun getEventHistory(deviceId: String? = null, limit: Int = 50): List<DeviceMessage> {
        if (!deviceId.isNullOrBlank()) {
            val entities = eventRepository.findByDeviceIdOrderByTimestampDesc(deviceId, PageRequest.of(0, limit))
            if (entities.isNotEmpty()) {
                return entities.map {
                    val map = try {
                        objectMapper.readValue(it.payloadJson, Map::class.java) as Map<String, Any?>
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    DeviceMessage(
                        type = MessageType.valueOf(it.eventType),
                        deviceId = it.deviceId,
                        timestamp = it.timestamp,
                        payload = map
                    )
                }
            }
        }
        return eventHistory.stream().limit(limit.toLong()).toList()
    }

    private fun sendJson(session: WebSocketSession, message: DeviceMessage) {
        try {
            if (session.isOpen) {
                val json = objectMapper.writeValueAsString(message)
                synchronized(session) {
                    session.sendMessage(TextMessage(json))
                }
            }
        } catch (e: IOException) {
            logger.error("Error al enviar mensaje a sesión ${session.id}: ${e.message}")
        }
    }
}
