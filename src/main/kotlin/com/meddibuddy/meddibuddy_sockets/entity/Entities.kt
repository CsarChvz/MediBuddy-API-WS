package com.meddibuddy.meddibuddy_sockets.entity

import jakarta.persistence.*
import java.time.Instant

enum class EstadoToma {
    PENDIENTE,
    DISPENSADA,
    TOMADA,
    POSPUESTA,
    NO_CONFIRMADA,
    TARDIA,
    ERROR
}

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
    var medicationName: String,

    @Column(nullable = false)
    var dose: String,

    val compartment: Int = 1,

    var scheduledHour: Int = 8,

    var scheduledMinute: Int = 0,

    var isActive: Boolean = true // Actividad 2: Atributo para borrado lógico
) {
    fun actualizarConfiguracion(clientType: String, nuevoNombre: String, nuevaDosis: String, nuevaHora: Int, nuevoMinuto: Int) {
        if (clientType != "CUIDADOR") {
            throw SecurityException("Acceso denegado: Solo el cuidador puede modificar el medicamento.")
        }
        this.medicationName = nuevoNombre
        this.dosis = nuevaDosis
        this.scheduledHour = nuevaHora
        this.scheduledMinute = nuevoMinuto
    }

    // 3. Actividad 2: Borrado lógico del medicamento[cite: 1, 21]
    fun aplicarBorradoLogico(clientType: String) {
        if (clientType != "CUIDADOR") {
            throw SecurityException("Acceso denegado: Solo el cuidador puede eliminar el medicamento.")
        }
        this.isActive = false
    }
}
