package com.meddibuddy.meddibuddy_sockets

import com.meddibuddy.meddibuddy_sockets.controller.*
import com.meddibuddy.meddibuddy_sockets.entity.EstadoToma
import com.meddibuddy.meddibuddy_sockets.entity.MedicationScheduleEntity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus

@SpringBootTest
class AuthControllerTest @Autowired constructor(
    private val authController: AuthController,
    private val deviceController: DeviceController
) {

    @Test
    fun `test register and login flow`() {
        val email = "cuidador_${System.currentTimeMillis()}@test.com"
        val registerReq = RegisterRequest(
            name = "Laura Martinez",
            email = email,
            password = "password123",
            role = "CUIDADOR"
        )

        // 1. Registro
        val regResponse = authController.register(registerReq)
        assertEquals(HttpStatus.CREATED, regResponse.statusCode)
        val authBody = regResponse.body as AuthResponse
        assertNotNull(authBody.token)
        assertEquals("Laura Martinez", authBody.name)
        assertEquals("CUIDADOR", authBody.role)
        val caregiverDeviceId = authBody.deviceId

        // 2. Login exitoso
        val loginReq = LoginRequest(email = email, password = "password123")
        val loginResponse = authController.login(loginReq)
        assertEquals(HttpStatus.OK, loginResponse.statusCode)
        val loginBody = loginResponse.body as AuthResponse
        assertNotNull(loginBody.token)
        assertEquals(caregiverDeviceId, loginBody.deviceId)

        // 3. Login fallido
        val badLogin = LoginRequest(email = email, password = "wrong_password")
        val badResponse = authController.login(badLogin)
        assertEquals(HttpStatus.UNAUTHORIZED, badResponse.statusCode)

        // 4. Generación de código de vinculación
        val genCodeReq = GenerateCodeRequest(
            caregiverUserId = authBody.userId,
            caregiverDeviceId = caregiverDeviceId
        )
        val codeResponse = authController.generatePairingCode(genCodeReq)
        assertEquals(HttpStatus.OK, codeResponse.statusCode)
        val codeBody = codeResponse.body as PairingCodeResponse
        assertEquals(6, codeBody.code.length)

        // 5. Vinculación del adulto mayor
        val linkReq = LinkCodeRequest(
            code = codeBody.code,
            patientDeviceId = "USER_ADULTO_123"
        )
        val linkResponse = authController.linkWithCode(linkReq)
        assertEquals(HttpStatus.OK, linkResponse.statusCode)
        val linkBody = linkResponse.body as LinkResponse
        assertTrue(linkBody.success)
        assertEquals(caregiverDeviceId, linkBody.caregiverDeviceId)
    }

    @Test
    fun `test schedule CRUD and logical delete with role verification`() {
        val testDeviceId = "ESP32_TEST_CRUD_${System.currentTimeMillis()}"

        // Crear schedule
        val createReq = CreateScheduleRequest(
            medicationName = "Losartan 50mg",
            dose = "1 tableta",
            compartment = 1,
            scheduledHour = 9,
            scheduledMinute = 30
        )
        val createdSchedule = deviceController.addMedicationSchedule(testDeviceId, createReq)
        assertNotNull(createdSchedule.id)
        val scheduleId = createdSchedule.id!!

        // Actualizar como CUIDADOR (exitoso)
        val updateReq = UpdateScheduleRequest(
            medicationName = "Losartan 100mg",
            dose = "2 tabletas",
            scheduledHour = 10,
            scheduledMinute = 0,
            clientType = "CUIDADOR"
        )
        val updateRes = deviceController.updateMedicationSchedule(testDeviceId, scheduleId, updateReq)
        assertEquals(HttpStatus.OK, updateRes.statusCode)
        val updatedSchedule = updateRes.body as MedicationScheduleEntity
        assertEquals("Losartan 100mg", updatedSchedule.medicationName)

        // Intentar actualizar como ADULTO_MAYOR (debe fallar con FORBIDDEN)
        val badUpdateReq = UpdateScheduleRequest(
            medicationName = "Intento No Autorizado",
            dose = "1 tableta",
            scheduledHour = 11,
            scheduledMinute = 0,
            clientType = "ADULTO_MAYOR"
        )
        val badUpdateRes = deviceController.updateMedicationSchedule(testDeviceId, scheduleId, badUpdateReq)
        assertEquals(HttpStatus.FORBIDDEN, badUpdateRes.statusCode)

        // Borrado lógico como CUIDADOR
        val deleteRes = deviceController.deleteMedicationSchedule(testDeviceId, scheduleId, "CUIDADOR")
        assertEquals(HttpStatus.OK, deleteRes.statusCode)
        val deleteBody = deleteRes.body as Map<*, *>
        assertEquals("DELETED", deleteBody["status"])
        assertEquals(false, deleteBody["isActive"])

        // Borrado lógico como ADULTO_MAYOR debe fallar
        val badDeleteRes = deviceController.deleteMedicationSchedule(testDeviceId, scheduleId, "ADULTO_MAYOR")
        assertEquals(HttpStatus.FORBIDDEN, badDeleteRes.statusCode)

        // Transición de estado a TOMADA
        val statusReq = UpdateScheduleStatusRequest(estadoToma = EstadoToma.TOMADA)
        val statusRes = deviceController.updateScheduleStatus(testDeviceId, scheduleId, statusReq)
        assertEquals(HttpStatus.OK, statusRes.statusCode)
        val statusBody = statusRes.body as MedicationScheduleEntity
        assertEquals(EstadoToma.TOMADA, statusBody.estadoToma)
    }
}
