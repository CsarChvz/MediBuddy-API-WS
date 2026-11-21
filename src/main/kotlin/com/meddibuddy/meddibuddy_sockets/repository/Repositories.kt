package com.meddibuddy.meddibuddy_sockets.repository

import com.meddibuddy.meddibuddy_sockets.entity.DeviceCommandEntity
import com.meddibuddy.meddibuddy_sockets.entity.DeviceEntity
import com.meddibuddy.meddibuddy_sockets.entity.DeviceEventEntity
import com.meddibuddy.meddibuddy_sockets.entity.MedicationScheduleEntity
import com.meddibuddy.meddibuddy_sockets.entity.PairingCodeEntity
import com.meddibuddy.meddibuddy_sockets.entity.UserEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional

@Repository
interface DeviceRepository : JpaRepository<DeviceEntity, String>

@Repository
interface DeviceEventRepository : JpaRepository<DeviceEventEntity, Long> {
    fun findByDeviceIdOrderByTimestampDesc(deviceId: String, pageable: Pageable): List<DeviceEventEntity>
    fun findByDeviceIdAndEventTypeOrderByTimestampDesc(deviceId: String, eventType: String, pageable: Pageable): List<DeviceEventEntity>
}

@Repository
interface DeviceCommandRepository : JpaRepository<DeviceCommandEntity, Long> {
    fun findByTargetDeviceIdOrderByCreatedAtDesc(targetDeviceId: String, pageable: Pageable): List<DeviceCommandEntity>
    fun findFirstByTargetDeviceIdAndActionAndStatusOrderByCreatedAtDesc(
        targetDeviceId: String,
        action: String,
        status: String
    ): DeviceCommandEntity?
}

@Repository
interface MedicationScheduleRepository : JpaRepository<MedicationScheduleEntity, Long> {
    fun findByDeviceIdAndIsActiveTrue(deviceId: String): List<MedicationScheduleEntity>
    fun findByDeviceIdAndCompartmentAndIsActiveTrue(deviceId: String, compartment: Int): List<MedicationScheduleEntity>
}

@Repository
interface UserRepository : JpaRepository<UserEntity, Long> {
    fun findByEmail(email: String): Optional<UserEntity>
    fun existsByEmail(email: String): Boolean
    fun findByDeviceId(deviceId: String): Optional<UserEntity>
}

@Repository
interface PairingCodeRepository : JpaRepository<PairingCodeEntity, Long> {
    fun findByCodeAndIsUsedFalseAndExpiresAtAfter(code: String, now: Instant): Optional<PairingCodeEntity>
    fun findByCaregiverUserIdAndIsUsedFalseAndExpiresAtAfter(caregiverUserId: Long, now: Instant): List<PairingCodeEntity>
}
