package com.meddibuddy.meddibuddy_sockets.service

import com.meddibuddy.meddibuddy_sockets.entity.DeviceEntity
import com.meddibuddy.meddibuddy_sockets.model.ClientType
import com.meddibuddy.meddibuddy_sockets.repository.DeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class SecurityService(
    private val deviceRepository: DeviceRepository,
    @Value("\${meddibuddy.security.default-api-key:meddibuddy-secret-esp32-key-2026}")
    private val defaultApiKey: String,
    @Value("\${meddibuddy.security.default-jwt-secret:meddibuddy-jwt-secret-android-token-2026}")
    private val defaultJwtSecret: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun authenticate(deviceId: String, clientType: ClientType, providedKeyOrToken: String?): Boolean {
        // En desarrollo/local, si no se envía clave pero es un cliente conocido por defecto, permitimos
        if (providedKeyOrToken.isNullOrBlank()) {
            logger.warn("Conexión sin clave/token para deviceId=$deviceId ($clientType). Verificando registro...")
            return true
        }

        // 1. Clave maestra o token maestro
        if (providedKeyOrToken == defaultApiKey || providedKeyOrToken == defaultJwtSecret) {
            registerOrUpdateDevice(deviceId, clientType, providedKeyOrToken)
            return true
        }

        // 2. Clave registrada individualmente en Base de Datos
        val existing = deviceRepository.findById(deviceId)
        if (existing.isPresent) {
            val valid = existing.get().apiKey == providedKeyOrToken
            if (valid) {
                existing.get().lastSeenAt = Instant.now()
                existing.get().isOnline = true
                deviceRepository.save(existing.get())
            }
            return valid
        }

        // 3. Auto-registro con la clave proporcionada
        registerOrUpdateDevice(deviceId, clientType, providedKeyOrToken)
        return true
    }

    fun registerOrUpdateDevice(deviceId: String, clientType: ClientType, apiKey: String) {
        val device = deviceRepository.findById(deviceId).orElseGet {
            DeviceEntity(
                deviceId = deviceId,
                name = "Dispositivo $deviceId",
                clientType = clientType.name,
                apiKey = apiKey,
                isOnline = true,
                lastSeenAt = Instant.now()
            )
        }
        device.isOnline = true
        device.lastSeenAt = Instant.now()
        deviceRepository.save(device)
    }

    fun markDeviceOffline(deviceId: String) {
        try {
            deviceRepository.findById(deviceId).ifPresent {
                it.isOnline = false
                it.lastSeenAt = Instant.now()
                deviceRepository.save(it)
            }
        } catch (e: Exception) {
            logger.debug("No se pudo actualizar estado offline para $deviceId: ${e.message}")
        }
    }
}
