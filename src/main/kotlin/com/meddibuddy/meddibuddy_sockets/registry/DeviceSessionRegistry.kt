package com.meddibuddy.meddibuddy_sockets.registry

import com.meddibuddy.meddibuddy_sockets.model.ClientType
import com.meddibuddy.meddibuddy_sockets.model.ConnectedDevice
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketSession
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

@Component
class DeviceSessionRegistry {
    private val logger = LoggerFactory.getLogger(javaClass)

    // sessionId -> WebSocketSession
    private val activeSessions = ConcurrentHashMap<String, WebSocketSession>()

    // sessionId -> ConnectedDevice metadata
    private val devicesBySession = ConcurrentHashMap<String, ConnectedDevice>()

    // deviceId -> sessionId (ESP32 activos)
    private val esp32Sessions = ConcurrentHashMap<String, String>()

    // targetDeviceId -> Set de sessionIds de Android suscritos
    private val subscribersByDevice = ConcurrentHashMap<String, MutableSet<String>>()

    // Clientes suscritos a todos los dispositivos (ej. dashboard web o monitor global)
    private val globalSubscribers = CopyOnWriteArraySet<String>()

    fun register(
        session: WebSocketSession,
        deviceId: String,
        clientType: ClientType,
        metadata: Map<String, Any?> = emptyMap()
    ): ConnectedDevice {
        val sessionId = session.id
        activeSessions[sessionId] = session

        val device = ConnectedDevice(
            deviceId = deviceId,
            clientType = clientType,
            sessionId = sessionId,
            remoteAddress = session.remoteAddress?.toString(),
            connectedAt = Instant.now(),
            lastSeen = Instant.now(),
            metadata = metadata.toMutableMap()
        )
        devicesBySession[sessionId] = device

        if (clientType == ClientType.ESP32) {
            esp32Sessions[deviceId] = sessionId
            logger.info("ESP32 registrado: deviceId=$deviceId, sessionId=$sessionId")
        } else {
            logger.info("Cliente $clientType registrado: id=$deviceId, sessionId=$sessionId")
        }

        return device
    }

    fun unregister(sessionId: String) {
        val device = devicesBySession.remove(sessionId)
        activeSessions.remove(sessionId)

        if (device != null) {
            if (device.clientType == ClientType.ESP32) {
                esp32Sessions.remove(device.deviceId)
                logger.info("ESP32 desconectado: deviceId=${device.deviceId}")
            } else {
                logger.info("Cliente ${device.clientType} desconectado: id=${device.deviceId}")
            }
        }

        globalSubscribers.remove(sessionId)
        subscribersByDevice.values.forEach { it.remove(sessionId) }
    }

    fun updateLastSeen(sessionId: String) {
        devicesBySession[sessionId]?.lastSeen = Instant.now()
    }

    fun subscribeToDevice(subscriberSessionId: String, targetDeviceId: String) {
        if (targetDeviceId == "*" || targetDeviceId.equals("ALL", ignoreCase = true)) {
            globalSubscribers.add(subscriberSessionId)
            logger.info("Sesión $subscriberSessionId suscrita a TODOS los dispositivos")
        } else {
            subscribersByDevice.computeIfAbsent(targetDeviceId) { CopyOnWriteArraySet() }.add(subscriberSessionId)
            logger.info("Sesión $subscriberSessionId suscrita a dispositivo: $targetDeviceId")
        }
    }

    fun unsubscribeFromDevice(subscriberSessionId: String, targetDeviceId: String) {
        if (targetDeviceId == "*" || targetDeviceId.equals("ALL", ignoreCase = true)) {
            globalSubscribers.remove(subscriberSessionId)
        } else {
            subscribersByDevice[targetDeviceId]?.remove(subscriberSessionId)
        }
    }

    fun getEsp32Session(deviceId: String): WebSocketSession? {
        val sessionId = esp32Sessions[deviceId] ?: return null
        return activeSessions[sessionId]
    }

    fun getSubscribersForDevice(deviceId: String): List<WebSocketSession> {
        val specific = subscribersByDevice[deviceId].orEmpty()
        val allIds = specific + globalSubscribers
        return allIds.mapNotNull { activeSessions[it] }.filter { it.isOpen }
    }

    fun getAllConnectedDevices(): List<ConnectedDevice> {
        return devicesBySession.values.toList()
    }

    fun getConnectedDevice(sessionId: String): ConnectedDevice? {
        return devicesBySession[sessionId]
    }
}
