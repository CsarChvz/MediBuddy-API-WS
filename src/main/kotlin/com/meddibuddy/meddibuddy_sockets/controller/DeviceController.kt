package com.meddibuddy.meddibuddy_sockets.controller

import com.meddibuddy.meddibuddy_sockets.entity.DeviceCommandEntity
import com.meddibuddy.meddibuddy_sockets.entity.DeviceEntity
import com.meddibuddy.meddibuddy_sockets.entity.MedicationScheduleEntity
import com.meddibuddy.meddibuddy_sockets.model.DeviceMessage
import com.meddibuddy.meddibuddy_sockets.repository.DeviceCommandRepository
import com.meddibuddy.meddibuddy_sockets.repository.DeviceRepository
import com.meddibuddy.meddibuddy_sockets.repository.MedicationScheduleRepository
import com.meddibuddy.meddibuddy_sockets.service.EventDispatcherService
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class CommandRequest(
    val action: String,
    val params: Map<String, Any?> = emptyMap()
)

data class CreateScheduleRequest(
    val medicationName: String,
    val dose: String,
    val compartment: Int = 1,
    val scheduledHour: Int,
    val scheduledMinute: Int
)

data class UpdateScheduleRequest(
    val medicationName: String,
    val dose: String,
    val scheduledHour: Int,
    val scheduledMinute: Int,
    val clientType: String = "CUIDADOR"
)

data class UpdateScheduleStatusRequest(
    val estadoToma: com.meddibuddy.meddibuddy_sockets.entity.EstadoToma
)

@RestController
@RequestMapping("/api/v1/devices")
@CrossOrigin(origins = ["*"])
class DeviceController(
    private val deviceRepository: DeviceRepository,
    private val commandRepository: DeviceCommandRepository,
    private val scheduleRepository: MedicationScheduleRepository,
    private val eventDispatcherService: EventDispatcherService
) {

    @GetMapping
    fun listDevices(): List<DeviceEntity> {
        return deviceRepository.findAll()
    }

    @GetMapping("/{deviceId}/history")
    fun getDeviceHistory(
        @PathVariable deviceId: String,
        @RequestParam(defaultValue = "50") limit: Int
    ): List<DeviceMessage> {
        return eventDispatcherService.getEventHistory(deviceId, limit)
    }

    @GetMapping("/{deviceId}/commands")
    fun getDeviceCommands(
        @PathVariable deviceId: String,
        @RequestParam(defaultValue = "20") limit: Int
    ): List<DeviceCommandEntity> {
        return commandRepository.findByTargetDeviceIdOrderByCreatedAtDesc(deviceId, PageRequest.of(0, limit))
    }

    @PostMapping("/{deviceId}/command")
    fun sendCommand(
        @PathVariable deviceId: String,
        @RequestBody request: CommandRequest
    ): ResponseEntity<Map<String, Any>> {
        val success = eventDispatcherService.sendDirectCommand(
            targetDeviceId = deviceId,
            action = request.action,
            params = request.params
        )

        return if (success) {
            ResponseEntity.ok(
                mapOf(
                    "status" to "SENT",
                    "deviceId" to deviceId,
                    "action" to request.action
                )
            )
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                mapOf(
                    "status" to "QUEUED_OFFLINE",
                    "message" to "El dispositivo $deviceId no está conectado actualmente. El comando fue registrado en BD."
                )
            )
        }
    }

    @GetMapping("/{deviceId}/schedules")
    fun getMedicationSchedules(@PathVariable deviceId: String): List<MedicationScheduleEntity> {
        return scheduleRepository.findByDeviceIdAndIsActiveTrue(deviceId)
    }

    @PostMapping("/{deviceId}/schedules")
    fun addMedicationSchedule(
        @PathVariable deviceId: String,
        @RequestBody req: CreateScheduleRequest
    ): MedicationScheduleEntity {
        val schedule = MedicationScheduleEntity(
            deviceId = deviceId,
            medicationName = req.medicationName,
            dose = req.dose,
            compartment = req.compartment,
            scheduledHour = req.scheduledHour,
            scheduledMinute = req.scheduledMinute,
            isActive = true
        )
        return scheduleRepository.save(schedule)
    }

    @PutMapping("/{deviceId}/schedules/{scheduleId}")
    fun updateMedicationSchedule(
        @PathVariable deviceId: String,
        @PathVariable scheduleId: Long,
        @RequestBody req: UpdateScheduleRequest
    ): ResponseEntity<Any> {
        val scheduleOpt = scheduleRepository.findById(scheduleId)
        if (scheduleOpt.isEmpty) {
            return ResponseEntity.notFound().build()
        }
        val schedule = scheduleOpt.get()
        if (schedule.deviceId != deviceId) {
            return ResponseEntity.badRequest().body(mapOf("error" to "El medicamento no pertenece al dispositivo indicado."))
        }

        try {
            schedule.actualizarConfiguracion(
                clientType = req.clientType,
                nuevoNombre = req.medicationName,
                nuevaDosis = req.dose,
                nuevaHora = req.scheduledHour,
                nuevoMinuto = req.scheduledMinute
            )
            val updated = scheduleRepository.save(schedule)
            return ResponseEntity.ok(updated)
        } catch (e: SecurityException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to e.message))
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }
    }

    @DeleteMapping("/{deviceId}/schedules/{scheduleId}")
    fun deleteMedicationSchedule(
        @PathVariable deviceId: String,
        @PathVariable scheduleId: Long,
        @RequestParam(defaultValue = "CUIDADOR") clientType: String
    ): ResponseEntity<Any> {
        val scheduleOpt = scheduleRepository.findById(scheduleId)
        if (scheduleOpt.isEmpty) {
            return ResponseEntity.notFound().build()
        }
        val schedule = scheduleOpt.get()
        if (schedule.deviceId != deviceId) {
            return ResponseEntity.badRequest().body(mapOf("error" to "El medicamento no pertenece al dispositivo indicado."))
        }

        try {
            schedule.aplicarBorradoLogico(clientType)
            scheduleRepository.save(schedule)
            return ResponseEntity.ok(mapOf("status" to "DELETED", "scheduleId" to scheduleId, "isActive" to false))
        } catch (e: SecurityException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to e.message))
        }
    }

    @PatchMapping("/{deviceId}/schedules/{scheduleId}/status")
    fun updateScheduleStatus(
        @PathVariable deviceId: String,
        @PathVariable scheduleId: Long,
        @RequestBody req: UpdateScheduleStatusRequest
    ): ResponseEntity<Any> {
        val scheduleOpt = scheduleRepository.findById(scheduleId)
        if (scheduleOpt.isEmpty) {
            return ResponseEntity.notFound().build()
        }
        val schedule = scheduleOpt.get()
        if (schedule.deviceId != deviceId) {
            return ResponseEntity.badRequest().body(mapOf("error" to "El medicamento no pertenece al dispositivo indicado."))
        }

        schedule.transicionarEstado(req.estadoToma)
        val updated = scheduleRepository.save(schedule)
        return ResponseEntity.ok(updated)
    }
}
