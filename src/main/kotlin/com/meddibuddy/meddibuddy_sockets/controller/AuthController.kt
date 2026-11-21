package com.meddibuddy.meddibuddy_sockets.controller

import com.meddibuddy.meddibuddy_sockets.entity.PairingCodeEntity
import com.meddibuddy.meddibuddy_sockets.entity.UserEntity
import com.meddibuddy.meddibuddy_sockets.model.ClientType
import com.meddibuddy.meddibuddy_sockets.repository.DeviceRepository
import com.meddibuddy.meddibuddy_sockets.repository.PairingCodeRepository
import com.meddibuddy.meddibuddy_sockets.repository.UserRepository
import com.meddibuddy.meddibuddy_sockets.service.SecurityService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class RegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    val role: String = "ADULTO_MAYOR",
    val customDeviceId: String? = null
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class AuthResponse(
    val token: String,
    val userId: Long,
    val name: String,
    val email: String,
    val role: String,
    val deviceId: String,
    val linkedDeviceId: String? = null
)

data class GenerateCodeRequest(
    val caregiverUserId: Long? = null,
    val caregiverDeviceId: String
)

data class PairingCodeResponse(
    val code: String,
    val expiresAt: String,
    val caregiverDeviceId: String
)

data class LinkCodeRequest(
    val code: String,
    val patientUserId: Long? = null,
    val patientDeviceId: String
)

data class LinkResponse(
    val success: Boolean,
    val message: String,
    val caregiverDeviceId: String?,
    val patientDeviceId: String
)

@RestController
@RequestMapping("/api/v1/auth")
@CrossOrigin(origins = ["*"])
class AuthController(
    private val userRepository: UserRepository,
    private val pairingCodeRepository: PairingCodeRepository,
    private val securityService: SecurityService
) {

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val encodedHash = digest.digest(password.toByteArray(StandardCharsets.UTF_8))
        return encodedHash.joinToString("") { "%02x".format(it) }
    }

    private fun generateToken(userId: Long, email: String): String {
        return "mb_tok_${userId}_" + UUID.randomUUID().toString().replace("-", "")
    }

    @PostMapping("/register")
    fun register(@RequestBody req: RegisterRequest): ResponseEntity<Any> {
        val normalizedEmail = req.email.trim().lowercase()
        if (normalizedEmail.isBlank() || req.password.isBlank() || req.name.isBlank()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Nombre, correo y contraseña son obligatorios."))
        }

        if (userRepository.existsByEmail(normalizedEmail)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "El correo ya se encuentra registrado."))
        }

        val role = req.role.trim().uppercase()
        val assignedDeviceId = req.customDeviceId?.ifBlank { null }
            ?: ("USER_" + role + "_" + UUID.randomUUID().toString().take(8).uppercase())

        val user = UserEntity(
            name = req.name.trim(),
            email = normalizedEmail,
            passwordHash = hashPassword(req.password),
            role = role,
            deviceId = assignedDeviceId
        )
        val savedUser = userRepository.save(user)
        val token = generateToken(savedUser.id!!, savedUser.email)

        // Registrar credencial en SecurityService
        securityService.registerOrUpdateDevice(
            deviceId = assignedDeviceId,
            clientType = ClientType.ANDROID,
            apiKey = token
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(
            AuthResponse(
                token = token,
                userId = savedUser.id,
                name = savedUser.name,
                email = savedUser.email,
                role = savedUser.role,
                deviceId = savedUser.deviceId,
                linkedDeviceId = savedUser.linkedDeviceId
            )
        )
    }

    @PostMapping("/login")
    fun login(@RequestBody req: LoginRequest): ResponseEntity<Any> {
        val normalizedEmail = req.email.trim().lowercase()
        val userOpt = userRepository.findByEmail(normalizedEmail)

        if (userOpt.isEmpty) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Credenciales incorrectas."))
        }

        val user = userOpt.get()
        if (user.passwordHash != hashPassword(req.password)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Credenciales incorrectas."))
        }

        val token = generateToken(user.id!!, user.email)

        // Actualizar credencial en SecurityService
        securityService.registerOrUpdateDevice(
            deviceId = user.deviceId,
            clientType = ClientType.ANDROID,
            apiKey = token
        )

        return ResponseEntity.ok(
            AuthResponse(
                token = token,
                userId = user.id,
                name = user.name,
                email = user.email,
                role = user.role,
                deviceId = user.deviceId,
                linkedDeviceId = user.linkedDeviceId
            )
        )
    }

    @PostMapping("/pairing/generate")
    fun generatePairingCode(@RequestBody req: GenerateCodeRequest): ResponseEntity<Any> {
        if (req.caregiverDeviceId.isBlank()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "caregiverDeviceId es requerido."))
        }

        val code = (100000..999999).random().toString()
        val expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES)

        val pairingCode = PairingCodeEntity(
            code = code,
            caregiverUserId = req.caregiverUserId ?: 0L,
            caregiverDeviceId = req.caregiverDeviceId,
            expiresAt = expiresAt
        )
        pairingCodeRepository.save(pairingCode)

        return ResponseEntity.ok(
            PairingCodeResponse(
                code = code,
                expiresAt = expiresAt.toString(),
                caregiverDeviceId = req.caregiverDeviceId
            )
        )
    }

    @PostMapping("/pairing/link")
    fun linkWithCode(@RequestBody req: LinkCodeRequest): ResponseEntity<Any> {
        val cleanCode = req.code.trim().replace(" ", "")
        val codeOpt = pairingCodeRepository.findByCodeAndIsUsedFalseAndExpiresAtAfter(cleanCode, Instant.now())

        if (codeOpt.isEmpty) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                LinkResponse(
                    success = false,
                    message = "Código inválido o expirado.",
                    caregiverDeviceId = null,
                    patientDeviceId = req.patientDeviceId
                )
            )
        }

        val pairing = codeOpt.get()
        pairing.isUsed = true
        pairing.usedByUserId = req.patientUserId
        pairingCodeRepository.save(pairing)

        // Vincular en la entidad del usuario si existe
        if (req.patientUserId != null) {
            userRepository.findById(req.patientUserId).ifPresent { user ->
                user.linkedDeviceId = pairing.caregiverDeviceId
                userRepository.save(user)
            }
        }

        return ResponseEntity.ok(
            LinkResponse(
                success = true,
                message = "Vinculación exitosa.",
                caregiverDeviceId = pairing.caregiverDeviceId,
                patientDeviceId = req.patientDeviceId
            )
        )
    }
}
