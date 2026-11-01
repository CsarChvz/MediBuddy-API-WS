package com.meddibuddy.meddibuddy_sockets.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "devices")
data class DeviceEntity(
    @Id
    val deviceId: String,

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false)
    val clientType: String,

    @Column(nullable = false)
    val apiKey: String,

    var isOnline: Boolean = false,

    var lastSeenAt: Instant? = null,

    val registeredAt: Instant = Instant.now()
)

@Entity
@Table(name = "device_events")
data class DeviceEventEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val deviceId: String,

    @Column(nullable = false)
    val eventType: String,

    @Column(columnDefinition = "TEXT", nullable = false)
    val payloadJson: String,

    val timestamp: Instant = Instant.now()
)

@Entity
@Table(name = "device_commands")
data class DeviceCommandEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val targetDeviceId: String,

    @Column(nullable = false)
    val issuedBy: String,

    @Column(nullable = false)
    val action: String,

    @Column(columnDefinition = "TEXT", nullable = false)
    val paramsJson: String,

    var status: String = "PENDING",

    val createdAt: Instant = Instant.now(),

    var executedAt: Instant? = null
)

@Entity
@Table(name = "medication_schedules")
data class MedicationScheduleEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val deviceId: String,

    @Column(nullable = false)
    val medicationName: String,

    @Column(nullable = false)
    val dose: String,

    val compartment: Int = 1,

    val scheduledHour: Int = 8,

    val scheduledMinute: Int = 0,

    var isActive: Boolean = true
)
