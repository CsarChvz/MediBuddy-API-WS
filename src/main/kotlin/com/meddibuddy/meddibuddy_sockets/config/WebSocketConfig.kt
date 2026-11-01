package com.meddibuddy.meddibuddy_sockets.config

import com.meddibuddy.meddibuddy_sockets.handler.DeviceWebSocketHandler
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val deviceWebSocketHandler: DeviceWebSocketHandler
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(deviceWebSocketHandler, "/ws/events", "/ws/devices")
            .setAllowedOrigins("*")
    }
}
