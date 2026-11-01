# MeddiBudy - Event-Driven WebSocket API (Kotlin / Spring Boot)

API orientada a eventos construida en **Kotlin** y **Spring Boot 3** diseñada para comunicar en tiempo real dispositivos embebidos (**ESP32**) con aplicaciones móviles (**Android**).

---

## 🏗️ Arquitectura de la Solución

```mermaid
flowchart LR
    ESP32["ESP32 (C++/Arduino)\nCliente WebSocket JSON"]
    Backend["API Kotlin (Spring Boot)\n/ws/events\n- DeviceSessionRegistry\n- EventDispatcher"]
    Android["App Android\n(Kotlin + OkHttp WebSocket)"]

    ESP32 -->|1. Envía Telemetría / Alertas| Backend
    Backend -->|2. Despacha en tiempo real| Android
    Android -->|3. Envía Comandos (ej. DISPENSE)| Backend
    Backend -->|4. Rutea al dispositivo| ESP32
    ESP32 -->|5. Confirma con COMMAND_ACK| Backend
    Backend -->|6. Notifica confirmación| Android
```

---

## 🚀 Inicio Rápido

### Requisitos
* JDK 21
* Python 3 (para simuladores de prueba, opcional)

### Compilar y Ejecutar

```bash
# Compilar y ejecutar pruebas unitarias e integración
./gradlew test

# Iniciar servidor en el puerto 8080
./gradlew bootRun
```

El servidor estará escuchando en:
* **WebSocket Endpoint:** `ws://localhost:8080/ws/events`
* **REST API:** `http://localhost:8080/api/v1/devices`

---

## 📡 Protocolo WebSocket

### Formato de Mensaje JSON
Todos los mensajes intercambiados comparten la misma estructura base:

```json
{
  "type": "TELEMETRY | ALERT | COMMAND | COMMAND_ACK | SUBSCRIBE | UNSUBSCRIBE | PING | PONG | CONNECT",
  "deviceId": "ID_DEL_EMISOR",
  "clientType": "ESP32 | ANDROID | WEB | SERVER",
  "targetDeviceId": "ID_DEL_DESTINO (opcional)",
  "payload": {
    "clave": "valor"
  }
}
```

### Tipos de Mensajes:
1. **`CONNECT`**: Registro inicial de un dispositivo o app.
2. **`SUBSCRIBE`**: Una app Android se suscribe a los eventos de un ESP32 (`targetDeviceId: "ESP32_MED_01"` o `"*"` para todos).
3. **`TELEMETRY`**: El ESP32 emite datos periódicos (batería, estado de sensores, etc.).
4. **`ALERT`**: El ESP32 o servidor emite una alerta prioritaria (ej. botón de pánico, dosis omitida).
5. **`COMMAND`**: La app Android envía una orden al ESP32 (ej. dispensar medicamento, encender zumbador).
6. **`COMMAND_ACK`**: El ESP32 confirma que ejecutó la orden física.

---

## 🧪 Pruebas con Simuladores

Se incluyen scripts en Python para probar la comunicación sin necesidad del hardware físico:

1. **Simulador ESP32:**
   ```bash
   ./scripts/simulate_esp32.py
   ```
   * Se conecta, emite telemetría periódica y escucha comandos.

2. **Simulador Android:**
   ```bash
   ./scripts/simulate_android.py
   ```
   * Se conecta, se suscribe al ESP32, muestra eventos en consola y envía un comando de prueba (`DISPENSE_MEDICATION`).

---

## 🔌 Guías de Integración

* **ESP32 (C++/Arduino):** Ver ejemplo completo en [`docs/esp32_example.ino`](docs/esp32_example.ino) usando la librería `arduinoWebSockets` y `ArduinoJson`.
* **Android (Kotlin):** Ver ejemplo de `AndroidWebSocketManager` en [`docs/android_kotlin_example.kt`](docs/android_kotlin_example.kt) usando `OkHttp WebSocket` y `Kotlin Flow`.

---

## 🌐 Endpoints REST de Diagnóstico

* `GET /api/v1/devices`: Lista de dispositivos conectados en tiempo real.
* `GET /api/v1/devices/{deviceId}/history`: Historial de los últimos eventos del dispositivo.
* `POST /api/v1/devices/{deviceId}/command`: Enviar un comando directo al ESP32 vía HTTP:
  ```json
  {
    "action": "BUZZER_ON",
    "params": { "durationMs": 1000 }
  }
  ```
# MediBuddy-API-WS
