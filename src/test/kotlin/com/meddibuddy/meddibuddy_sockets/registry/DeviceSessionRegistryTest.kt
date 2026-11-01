package com.meddibuddy.meddibuddy_sockets.registry

import com.meddibuddy.meddibuddy_sockets.model.ClientType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.web.socket.WebSocketSession

class DeviceSessionRegistryTest {

    private lateinit var registry: DeviceSessionRegistry

    @BeforeEach
    fun setUp() {
        registry = DeviceSessionRegistry()
    }

    @Test
    fun `test register and unregister ESP32 device`() {
        val mockSession = Mockito.mock(WebSocketSession::class.java)
        Mockito.`when`(mockSession.id).thenReturn("session-esp32-1")
        Mockito.`when`(mockSession.isOpen).thenReturn(true)

        val device = registry.register(
            session = mockSession,
            deviceId = "ESP32_DEV_01",
            clientType = ClientType.ESP32
        )

        assertEquals("ESP32_DEV_01", device.deviceId)
        assertEquals(ClientType.ESP32, device.clientType)
        assertEquals(1, registry.getAllConnectedDevices().size)
        assertNotNull(registry.getEsp32Session("ESP32_DEV_01"))

        registry.unregister("session-esp32-1")
        assertEquals(0, registry.getAllConnectedDevices().size)
        assertNull(registry.getEsp32Session("ESP32_DEV_01"))
    }

    @Test
    fun `test Android subscriber routing`() {
        val espSession = Mockito.mock(WebSocketSession::class.java)
        Mockito.`when`(espSession.id).thenReturn("session-esp")
        Mockito.`when`(espSession.isOpen).thenReturn(true)

        val androidSession = Mockito.mock(WebSocketSession::class.java)
        Mockito.`when`(androidSession.id).thenReturn("session-android")
        Mockito.`when`(androidSession.isOpen).thenReturn(true)

        registry.register(espSession, "ESP32_DEV_01", ClientType.ESP32)
        registry.register(androidSession, "USER_APP_01", ClientType.ANDROID)

        // Suscribir Android al ESP32
        registry.subscribeToDevice("session-android", "ESP32_DEV_01")

        val subscribers = registry.getSubscribersForDevice("ESP32_DEV_01")
        assertEquals(1, subscribers.size)
        assertEquals("session-android", subscribers[0].id)
    }
}
