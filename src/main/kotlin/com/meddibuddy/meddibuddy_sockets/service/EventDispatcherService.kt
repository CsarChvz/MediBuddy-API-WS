package com.meddibuddy.meddibuddy_sockets.service

import com.meddibuddy.meddibuddy_sockets.entity.DeviceCommandEntity
import com.meddibuddy.meddibuddy_sockets.entity.DeviceEventEntity
import com.meddibuddy.meddibuddy_sockets.entity.EstadoToma
import com.meddibuddy.meddibuddy_sockets.entity.MedicationScheduleEntity
import com.meddibuddy.meddibuddy_sockets.model.ClientType
import com.meddibuddy.meddibuddy_sockets.model.DeviceMessage
import com.meddibuddy.meddibuddy_sockets.model.MessageType
import com.meddibuddy.meddibuddy_sockets.registry.DeviceSessionRegistry
import com.meddibuddy.meddibuddy_sockets.repository.DeviceCommandRepository
import com.meddibuddy.meddibuddy_sockets.repository.DeviceEventRepository
import com.meddibuddy.meddibuddy_sockets.repository.DeviceRepository
import com.meddibuddy.meddibuddy_sockets.repository.MedicationScheduleRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.ConcurrentLinkedDeque

@Service
class EventDispatcherService(
    private val sessionRegistry: DeviceSessionRegistry,
    private val securityService: SecurityService,
    private val eventRepository: DeviceEventRepository,
    private val commandRepository: DeviceCommandRepository,
    private val scheduleRepository: MedicationScheduleRepository,
    private val deviceRepository: DeviceRepository,
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
            MessageType.MEDICATION_CONFIG -> handleMedicationConfig(message, session)
            MessageType.MEDICATION_DELETE -> handleMedicationDelete(message, session)
            MessageType.INTAKE_STATUS -> handleIntakeStatus(message, session)
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

        // 2. Detección de botón físico o apertura de compartimento
        val buttonPressed = message.payload["buttonPressed"] == true
        val boxOpened = message.payload["pillBoxClosed"] == false || message.payload["pillBoxOpened"] == true
        val compartment = (message.payload["compartment"] as? Number)?.toInt() ?: 1

        if (buttonPressed || boxOpened) {
            procesarEventoBotonFisico(message.deviceId, compartment)
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
        val action = message.payload["action"]?.toString() ?: "UNKNOWN"

        // Enrutamiento de comandos de negocio
        when (action) {
            "UPDATE_MEDICATION" -> {
                handleMedicationConfig(message, senderSession)
                return
            }
            "DELETE_MEDICATION" -> {
                handleMedicationDelete(message, senderSession)
                return
            }
            "BUTTON_PRESSED" -> {
                val compartment = (message.payload["compartment"] as? Number)?.toInt() ?: 1
                procesarEventoBotonFisico(message.deviceId, compartment)
                return
            }
        }

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

        val paramsJson = try { objectMapper.writeValueAsString(message.payload) } catch (_: Exception) { "{}" }

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

    // ========================================================================
    // Actividad 2: Lógica de Negocio POO y Reglas de Seguridad
    // ========================================================================

    /**
     * Regla 2: Configuración protegida del medicamento.
     * Invoca los métodos de encapsulamiento POO en MedicationScheduleEntity.
     * Si el rol es "ADULTO_MAYOR", la entidad lanza SecurityException y aquí se maneja adecuadamente.
     */
    fun actualizarConfiguracionMedicamento(
        scheduleId: Long,
        clientType: String,
        nuevoNombre: String,
        nuevaDosis: String,
        nuevaHora: Int,
        nuevoMinuto: Int
    ): MedicationScheduleEntity {
        val schedule = scheduleRepository.findById(scheduleId).orElseThrow {
            NoSuchElementException("No se encontró el horario de medicamento con ID: $scheduleId")
        }

        // Llamada directa al método de negocio de la entidad POO
        schedule.actualizarConfiguracion(
            clientType = clientType,
            nuevoNombre = nuevoNombre,
            nuevaDosis = nuevaDosis,
            nuevaHora = nuevaHora,
            nuevoMinuto = nuevoMinuto
        )

        val actualizado = scheduleRepository.save(schedule)
        logger.info("✅ Medicamento [ID: $scheduleId] actualizado por el rol '$clientType'")

        notificarCambioHorario(actualizado, "MEDICATION_UPDATED")
        return actualizado
    }

    /**
     * Regla 3: Borrado lógico del medicamento.
     * Cambia isActive = false para preservar historial.
     * Solo permitido para "CUIDADOR".
     */
    fun aplicarBorradoLogicoMedicamento(
        scheduleId: Long,
        clientType: String
    ): MedicationScheduleEntity {
        val schedule = scheduleRepository.findById(scheduleId).orElseThrow {
            NoSuchElementException("No se encontró el horario de medicamento con ID: $scheduleId")
        }

        // Llamada directa al método de encapsulamiento POO
        schedule.aplicarBorradoLogico(clientType)

        val desactivado = scheduleRepository.save(schedule)
        logger.info("🗑️ Borrado lógico aplicado al medicamento [ID: $scheduleId] por '$clientType'")

        notificarCambioHorario(desactivado, "MEDICATION_DELETED")
        return desactivado
    }

    /**
     * Regla 1: Transición de estados de la toma (TOMADA vs TARDIA).
     * Se ejecuta cuando el pastillero físico reporta pulsación del botón.
     */
    fun procesarEventoBotonFisico(
        deviceId: String,
        compartment: Int,
        horaActual: LocalTime = LocalTime.now()
    ): MedicationScheduleEntity? {
        val schedules = scheduleRepository.findByDeviceIdAndCompartmentAndIsActiveTrue(deviceId, compartment)
        val schedule = schedules.firstOrNull()

        // Llamada a la regla POO de la entidad si existe un horario programado
        val nuevoEstado = schedule?.registrarTomaFisica(horaActual) ?: EstadoToma.TOMADA
        val guardado = schedule?.let { scheduleRepository.save(it) }

        if (guardado != null) {
            logger.info("💊 Toma procesada para medicamento '${guardado.medicationName}'. Estado resultante: $nuevoEstado a las $horaActual")
        } else {
            logger.info("💊 Evento de toma detectado en dispositivo $deviceId (compartimento $compartment) sin horario previo registrado.")
        }

        val alertPayload = mutableMapOf<String, Any?>(
            "alertType" to "MEDICATION_TAKEN",
            "compartment" to compartment,
            "estadoToma" to nuevoEstado.name,
            "horaRegistro" to horaActual.toString(),
            "message" to "Compartimento abierto: Toma de medicamento detectada"
        )
        if (guardado != null) {
            alertPayload["scheduleId"] = guardado.id
            alertPayload["medicationName"] = guardado.medicationName
            alertPayload["horaProgramada"] = "${guardado.scheduledHour}:${guardado.scheduledMinute}"
        }

        val alert = DeviceMessage(
            type = MessageType.ALERT,
            deviceId = deviceId,
            clientType = ClientType.ESP32,
            payload = alertPayload
        )
        recordEvent(alert)
        persistEvent(alert)
        forwardToSubscribers(alert)

        return guardado
    }

    /**
     * Manejador WebSocket para peticiones de configuración de medicamentos.
     */
    private fun handleMedicationConfig(message: DeviceMessage, session: WebSocketSession) {
        val scheduleId = (message.payload["scheduleId"] as? Number)?.toLong()
            ?: message.payload["scheduleId"]?.toString()?.toLongOrNull()
        val medicationName = message.payload["medicationName"]?.toString()
        val dose = message.payload["dose"]?.toString()
        val hour = (message.payload["scheduledHour"] as? Number)?.toInt()
            ?: message.payload["scheduledHour"]?.toString()?.toIntOrNull()
        val minute = (message.payload["scheduledMinute"] as? Number)?.toInt()
            ?: message.payload["scheduledMinute"]?.toString()?.toIntOrNull()

        val clientType = resolveClientRole(message)

        if (scheduleId == null || medicationName.isNullOrBlank() || dose.isNullOrBlank() || hour == null || minute == null) {
            sendJson(session, DeviceMessage(
                type = MessageType.ALERT,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                payload = mapOf(
                    "status" to "ERROR",
                    "error" to "Parámetros incompletos (requerido: scheduleId, medicationName, dose, scheduledHour, scheduledMinute)"
                )
            ))
            return
        }

        try {
            val actualizado = actualizarConfiguracionMedicamento(
                scheduleId = scheduleId,
                clientType = clientType,
                nuevoNombre = medicationName,
                nuevaDosis = dose,
                nuevaHora = hour,
                nuevoMinuto = minute
            )

            sendJson(session, DeviceMessage(
                type = MessageType.COMMAND_ACK,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                targetDeviceId = message.deviceId,
                payload = mapOf(
                    "status" to "SUCCESS",
                    "scheduleId" to actualizado.id,
                    "medicationName" to actualizado.medicationName,
                    "dose" to actualizado.dose,
                    "scheduledHour" to actualizado.scheduledHour,
                    "scheduledMinute" to actualizado.scheduledMinute
                )
            ))
        } catch (e: SecurityException) {
            // Manejo estricto si es un Adulto Mayor o no autorizado
            logger.warn("⚠️ Intento no autorizado bloqueado: ${e.message}")
            sendJson(session, DeviceMessage(
                type = MessageType.ALERT,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                targetDeviceId = message.deviceId,
                payload = mapOf(
                    "status" to "FORBIDDEN",
                    "error" to (e.message ?: "Acceso denegado: Solo el CUIDADOR puede configurar medicamentos"),
                    "errorCode" to "SECURITY_VIOLATION"
                )
            ))
        } catch (e: Exception) {
            logger.error("Error al configurar medicamento: ${e.message}")
            sendJson(session, DeviceMessage(
                type = MessageType.ALERT,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                targetDeviceId = message.deviceId,
                payload = mapOf("status" to "ERROR", "error" to (e.message ?: "Error interno"))
            ))
        }
    }

    /**
     * Manejador WebSocket para peticiones de borrado lógico de medicamentos.
     */
    private fun handleMedicationDelete(message: DeviceMessage, session: WebSocketSession) {
        val scheduleId = (message.payload["scheduleId"] as? Number)?.toLong()
            ?: message.payload["scheduleId"]?.toString()?.toLongOrNull()

        val clientType = resolveClientRole(message)

        if (scheduleId == null) {
            sendJson(session, DeviceMessage(
                type = MessageType.ALERT,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                payload = mapOf("status" to "ERROR", "error" to "Parámetro 'scheduleId' requerido")
            ))
            return
        }

        try {
            val desactivado = aplicarBorradoLogicoMedicamento(scheduleId, clientType)
            sendJson(session, DeviceMessage(
                type = MessageType.COMMAND_ACK,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                targetDeviceId = message.deviceId,
                payload = mapOf(
                    "status" to "SUCCESS",
                    "scheduleId" to desactivado.id,
                    "isActive" to desactivado.isActive
                )
            ))
        } catch (e: SecurityException) {
            logger.warn("⚠️ Intento no autorizado de borrado lógico bloqueado: ${e.message}")
            sendJson(session, DeviceMessage(
                type = MessageType.ALERT,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                targetDeviceId = message.deviceId,
                payload = mapOf(
                    "status" to "FORBIDDEN",
                    "error" to (e.message ?: "Acceso denegado: Solo el CUIDADOR puede eliminar medicamentos"),
                    "errorCode" to "SECURITY_VIOLATION"
                )
            ))
        } catch (e: Exception) {
            logger.error("Error al aplicar borrado lógico: ${e.message}")
            sendJson(session, DeviceMessage(
                type = MessageType.ALERT,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                targetDeviceId = message.deviceId,
                payload = mapOf("status" to "ERROR", "error" to (e.message ?: "Error interno"))
            ))
        }
    }

    /**
     * Manejador para transicionar el estado de la toma de manera directa o manual.
     */
    private fun handleIntakeStatus(message: DeviceMessage, session: WebSocketSession) {
        val scheduleId = (message.payload["scheduleId"] as? Number)?.toLong()
            ?: message.payload["scheduleId"]?.toString()?.toLongOrNull()
        val nuevoEstadoStr = message.payload["estadoToma"]?.toString()

        if (scheduleId == null || nuevoEstadoStr == null) {
            sendJson(session, DeviceMessage(
                type = MessageType.ALERT,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                payload = mapOf("status" to "ERROR", "error" to "Parámetros incompletos (scheduleId y estadoToma requeridos)")
            ))
            return
        }

        try {
            val nuevoEstado = EstadoToma.valueOf(nuevoEstadoStr.uppercase())
            val schedule = scheduleRepository.findById(scheduleId).orElseThrow {
                NoSuchElementException("Horario de medicamento no encontrado")
            }
            schedule.transicionarEstado(nuevoEstado)
            val guardado = scheduleRepository.save(schedule)

            sendJson(session, DeviceMessage(
                type = MessageType.COMMAND_ACK,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                targetDeviceId = message.deviceId,
                payload = mapOf(
                    "status" to "SUCCESS",
                    "scheduleId" to guardado.id,
                    "estadoToma" to guardado.estadoToma.name
                )
            ))

            notificarCambioHorario(guardado, "INTAKE_STATUS_UPDATED")
        } catch (e: Exception) {
            sendJson(session, DeviceMessage(
                type = MessageType.ALERT,
                deviceId = "SERVER",
                clientType = ClientType.SERVER,
                payload = mapOf("status" to "ERROR", "error" to (e.message ?: "Error al actualizar estado"))
            ))
        }
    }

    /**
     * Obtiene el rol del cliente (CUIDADOR vs ADULTO_MAYOR) examinando:
     * 1. El payload directo del mensaje.
     * 2. La entidad DeviceEntity registrada en la base de datos para ese deviceId.
     * 3. El clientType del enum en DeviceMessage.
     */
    private fun resolveClientRole(message: DeviceMessage): String {
        return message.payload["clientType"]?.toString()
            ?: message.payload["role"]?.toString()
            ?: deviceRepository.findById(message.deviceId).map { it.clientType }.orElse(message.clientType.name)
    }

    private fun notificarCambioHorario(schedule: MedicationScheduleEntity, motivo: String) {
        val notif = DeviceMessage(
            type = MessageType.ALERT,
            deviceId = schedule.deviceId,
            clientType = ClientType.SERVER,
            payload = mapOf(
                "alertType" to motivo,
                "scheduleId" to schedule.id,
                "medicationName" to schedule.medicationName,
                "dose" to schedule.dose,
                "scheduledHour" to schedule.scheduledHour,
                "scheduledMinute" to schedule.scheduledMinute,
                "compartment" to schedule.compartment,
                "estadoToma" to schedule.estadoToma.name,
                "isActive" to schedule.isActive
            )
        )
        recordEvent(notif)
        persistEvent(notif)
        forwardToSubscribers(notif)
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
