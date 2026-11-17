package com.meddibuddy.meddibuddy_sockets

import com.meddibuddy.meddibuddy_sockets.entity.EstadoToma
import com.meddibuddy.meddibuddy_sockets.entity.MedicationScheduleEntity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalTime

class MedicationBusinessRulesTest {

    @Test
    fun `Regla 1 - EstadoToma debe contener los 7 estados exactos requeridos`() {
        val expectedStates = setOf(
            "PENDIENTE",
            "DISPENSADA",
            "TOMADA",
            "POSPUESTA",
            "NO_CONFIRMADA",
            "TARDIA",
            "ERROR"
        )
        val actualStates = EstadoToma.entries.map { it.name }.toSet()
        assertEquals(expectedStates, actualStates)
        assertEquals(7, EstadoToma.entries.size)
    }

    @Test
    fun `Regla 2 - CUIDADOR puede actualizar la configuracion del medicamento exitosamente`() {
        val schedule = MedicationScheduleEntity(
            id = 1L,
            deviceId = "ESP32_DEV_01",
            medicationName = "Paracetamol",
            dose = "500mg",
            scheduledHour = 8,
            scheduledMinute = 0,
            isActive = true
        )

        schedule.actualizarConfiguracion(
            clientType = "CUIDADOR",
            nuevoNombre = "Ibuprofeno",
            nuevaDosis = "400mg",
            nuevaHora = 14,
            nuevoMinuto = 30
        )

        assertEquals("Ibuprofeno", schedule.medicationName)
        assertEquals("400mg", schedule.dose)
        assertEquals(14, schedule.scheduledHour)
        assertEquals(30, schedule.scheduledMinute)
    }

    @Test
    fun `Regla 2 - ADULTO_MAYOR lanza SecurityException al intentar modificar el medicamento`() {
        val schedule = MedicationScheduleEntity(
            id = 1L,
            deviceId = "ESP32_DEV_01",
            medicationName = "Paracetamol",
            dose = "500mg",
            scheduledHour = 8,
            scheduledMinute = 0,
            isActive = true
        )

        val exception = assertThrows<SecurityException> {
            schedule.actualizarConfiguracion(
                clientType = "ADULTO_MAYOR",
                nuevoNombre = "Aspirina",
                nuevaDosis = "100mg",
                nuevaHora = 9,
                nuevoMinuto = 0
            )
        }

        assertTrue(exception.message!!.contains("Acceso denegado"))
        assertEquals("Paracetamol", schedule.medicationName) // Asegurar que los datos no cambiaron
    }

    @Test
    fun `Regla 3 - CUIDADOR puede aplicar borrado logico apagando isActive`() {
        val schedule = MedicationScheduleEntity(
            id = 1L,
            deviceId = "ESP32_DEV_01",
            medicationName = "Losartan",
            dose = "50mg",
            isActive = true
        )

        schedule.aplicarBorradoLogico("CUIDADOR")

        assertFalse(schedule.isActive, "isActive debe ser falso tras el borrado lógico")
    }

    @Test
    fun `Regla 3 - ADULTO_MAYOR no puede aplicar borrado logico y lanza SecurityException`() {
        val schedule = MedicationScheduleEntity(
            id = 1L,
            deviceId = "ESP32_DEV_01",
            medicationName = "Losartan",
            dose = "50mg",
            isActive = true
        )

        val exception = assertThrows<SecurityException> {
            schedule.aplicarBorradoLogico("ADULTO_MAYOR")
        }

        assertTrue(exception.message!!.contains("Acceso denegado"))
        assertTrue(schedule.isActive, "isActive debe permanecer intacto en true")
    }

    @Test
    fun `Transicion de Estados - registrarTomaFisica a tiempo transiciona a TOMADA`() {
        val schedule = MedicationScheduleEntity(
            id = 1L,
            deviceId = "ESP32_DEV_01",
            medicationName = "Metformina",
            dose = "850mg",
            scheduledHour = 10,
            scheduledMinute = 0,
            estadoToma = EstadoToma.PENDIENTE
        )

        // Se registra a las 10:15 (dentro de los 30 min de tolerancia)
        val horaToma = LocalTime.of(10, 15)
        val nuevoEstado = schedule.registrarTomaFisica(horaActual = horaToma, ventanaToleranciaMinutos = 30)

        assertEquals(EstadoToma.TOMADA, nuevoEstado)
        assertEquals(EstadoToma.TOMADA, schedule.estadoToma)
    }

    @Test
    fun `Transicion de Estados - registrarTomaFisica fuera de tiempo transiciona a TARDIA`() {
        val schedule = MedicationScheduleEntity(
            id = 1L,
            deviceId = "ESP32_DEV_01",
            medicationName = "Metformina",
            dose = "850mg",
            scheduledHour = 10,
            scheduledMinute = 0,
            estadoToma = EstadoToma.PENDIENTE
        )

        // Se registra a las 10:45 (excede los 30 min de tolerancia)
        val horaToma = LocalTime.of(10, 45)
        val nuevoEstado = schedule.registrarTomaFisica(horaActual = horaToma, ventanaToleranciaMinutos = 30)

        assertEquals(EstadoToma.TARDIA, nuevoEstado)
        assertEquals(EstadoToma.TARDIA, schedule.estadoToma)
    }
}
