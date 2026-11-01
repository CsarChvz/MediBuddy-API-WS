package com.meddibuddy.meddibuddy_sockets

import com.meddibuddy.meddibuddy_sockets.model.ClientType
import com.meddibuddy.meddibuddy_sockets.model.DeviceMessage
import com.meddibuddy.meddibuddy_sockets.model.MessageType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val objectMapper = ObjectMapper()

    @Test
    fun `test end to end event dispatch from ESP32 to Android subscriber`() {
        val client = StandardWebSocketClient()
        val wsUri = "ws://localhost:$port/ws/events"

        val androidReceivedTelemetry = CompletableFuture<DeviceMessage>()
        val androidReceivedAlert = CompletableFuture<DeviceMessage>()

        // 1. Conectar Android y suscribirse a ESP32_PATIENT_1
        val androidHandler = object : TextWebSocketHandler() {
            override fun afterConnectionEstablished(session: WebSocketSession) {
                // Registrarse como cliente Android
                val connectMsg = DeviceMessage(
                    type = MessageType.CONNECT,
                    deviceId = "ANDROID_PHONE_01",
                    clientType = ClientType.ANDROID
                )
                session.sendMessage(TextMessage(objectMapper.writeValueAsString(connectMsg)))

                // Suscribirse al ESP32
                val subMsg = DeviceMessage(
                    type = MessageType.SUBSCRIBE,
                    deviceId = "ANDROID_PHONE_01",
                    clientType = ClientType.ANDROID,
                    targetDeviceId = "ESP32_PATIENT_1"
                )
                session.sendMessage(TextMessage(objectMapper.writeValueAsString(subMsg)))
            }

            override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
                val devMsg = objectMapper.readValue(message.payload, DeviceMessage::class.java)
                if (devMsg.type == MessageType.TELEMETRY) {
                    androidReceivedTelemetry.complete(devMsg)
                } else if (devMsg.type == MessageType.ALERT) {
                    androidReceivedAlert.complete(devMsg)
                }
            }
        }

        val androidSession = client.execute(androidHandler, wsUri).get(5, TimeUnit.SECONDS)

        // Breve espera para que el registro del suscriptor se complete
        Thread.sleep(500)

        // 2. Conectar ESP32
        val espHandler = object : TextWebSocketHandler() {
            override fun afterConnectionEstablished(session: WebSocketSession) {
                val connectMsg = DeviceMessage(
                    type = MessageType.CONNECT,
                    deviceId = "ESP32_PATIENT_1",
                    clientType = ClientType.ESP32
                )
                session.sendMessage(TextMessage(objectMapper.writeValueAsString(connectMsg)))
            }
        }

        val espSession = client.execute(espHandler, wsUri).get(5, TimeUnit.SECONDS)
        Thread.sleep(300)

        // 3. ESP32 envía telemetría indicando pastillero abierto
        val telemetry = DeviceMessage(
            type = MessageType.TELEMETRY,
            deviceId = "ESP32_PATIENT_1",
            clientType = ClientType.ESP32,
            payload = mapOf(
                "battery" to 95,
                "pillBoxOpened" to true,
                "compartment" to 2
            )
        )
        espSession.sendMessage(TextMessage(objectMapper.writeValueAsString(telemetry)))

        // 4. Verificar que Android recibió la telemetría y la alerta de toma en tiempo real
        val receivedTele = androidReceivedTelemetry.get(5, TimeUnit.SECONDS)
        assertEquals(MessageType.TELEMETRY, receivedTele.type)
        assertEquals("ESP32_PATIENT_1", receivedTele.deviceId)
        assertEquals(95, (receivedTele.payload["battery"] as Number).toInt())
        assertEquals(true, receivedTele.payload["pillBoxOpened"])

        val receivedAlert = androidReceivedAlert.get(5, TimeUnit.SECONDS)
        assertEquals(MessageType.ALERT, receivedAlert.type)
        assertEquals("MEDICATION_TAKEN", receivedAlert.payload["alertType"])

        androidSession.close()
        espSession.close()
    }
}
