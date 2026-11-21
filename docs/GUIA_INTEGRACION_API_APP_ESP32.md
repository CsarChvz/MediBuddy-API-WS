# Guía Integral de Integración: API Backend, Aplicación Móvil Android y ESP-32 (Pastillero Inteligente)

Este documento detalla la arquitectura, el despliegue en servidor y el protocolo de comunicación para interconectar el backend **MediBuddy-API-WS**, la aplicación móvil **MediBuddy (Android)** y el microcontrolador **ESP-32**.

---

## 1. Arquitectura General del Sistema

```mermaid
flowchart TD
    subgraph Hardware["Capa de Hardware IoT (Pastillero)"]
        ESP32["ESP-32 NodeMCU\n- Servomotor (GPIO 19)\n- Buzzer / LED (GPIO 18)\n- Sensor Magnético Tapa (GPIO 4)\n- Botón Físico de Toma (GPIO 5)\n- Divisor Tensión Batería (ADC GPIO 34)"]
    end

    subgraph Servidor["Servidor Cloud / VPS (Docker / Ubuntu)"]
        direction TB
        Nginx["Reverse Proxy (Nginx / SSL)\n- Puerto 80 / 443\n- Proxy HTTP & WebSocket Upgrade"]
        API["Backend Kotlin + Spring Boot 3\n(Puerto 8080)"]
        DB[(PostgreSQL 16 / H2)]
        
        Nginx -->|Proxy Pass /api| API
        Nginx -->|Upgrade /ws| API
        API <--> DB
    end

    subgraph Mobile["Capa Móvil (Android)"]
        App["App MediBuddy (Kotlin)\n- Retrofit 2 (Auth & Sincronización REST)\n- OkHttp WebSocket (Alertas en Tiempo Real)\n- SessionManager & SharedPreferences"]
    end

    ESP32 <-->|WebSocket Seguro + API Key\n(ws://o wss://.../ws/events)| Nginx
    App <-->|HTTP REST /api/v1/auth & /api/v1/devices| Nginx
    App <-->|WebSocket Alertas /ws/events| Nginx
```

---

## 2. Despliegue de la API en Servidor

### Requisitos del Servidor
- Ubuntu 22.04+ / Debian 12 / VPS (AWS EC2, DigitalOcean, Hetzner, etc.)
- Docker y Docker Compose instalados.
- Java 21 (si se ejecuta directamente el JAR sin contenedor).

### Variables de Entorno de la API (Configuración Modular sin Hardcoding)
El backend lee todas sus credenciales y puertos de variables de entorno:

| Variable | Descripción | Valor por Defecto |
| :--- | :--- | :--- |
| `PORT` | Puerto HTTP del servicio Spring Boot | `8080` |
| `SPRING_DATASOURCE_URL` | URL de conexión a la base de datos | `jdbc:h2:mem:meddibuddy_db` |
| `SPRING_DATASOURCE_USERNAME` | Usuario de base de datos | `sa` |
| `SPRING_DATASOURCE_PASSWORD` | Contraseña de base de datos | *(vacío)* |
| `MEDDIBUDDY_API_KEY` | Clave maestra para microcontroladores ESP-32 | `meddibuddy-secret-esp32-key-2026` |
| `MEDDIBUDDY_JWT_SECRET` | Clave secreta para tokens de apps Android | `meddibuddy-jwt-secret-android-token-2026` |
| `H2_CONSOLE_ENABLED` | Habilita consola H2 (`/h2-console`) | `false` (en prod) |

### Despliegue con Docker Compose
En la carpeta `MediBuddy-API-WS`, ejecutar:
```bash
# Compilar el artefacto JAR
./gradlew bootJar

# Iniciar la base de datos PostgreSQL y la API
docker compose up -d
```

### Configuración de Nginx con Soporte para WebSockets
Si se utiliza un dominio con SSL (`https://tu-servidor.com`):
```nginx
server {
    server_name tu-servidor.com;

    # Endpoints REST
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # Conexión WebSocket en tiempo real
    location /ws/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "Upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }
}
```

---

## 3. Conexión de la Aplicación Móvil (Android)

La aplicación móvil en la rama `feature/api-connection` utiliza una arquitectura modular:

### Configuración Modular de URL
Sin código hardcodeado, la URL se inyecta por variable de entorno de compilación o archivo `local.properties`:
```bash
# Ejemplo al compilar con Gradle apuntando a un servidor de producción:
export MEDIBUDDY_API_URL="https://tu-servidor.com/"
export MEDIBUDDY_WS_URL="wss://tu-servidor.com/ws/events"
./gradlew assembleRelease
```
En desarrollo local con emulador de Android Studio, utiliza automáticamente:
- API REST: `http://10.0.2.2:8080/`
- WebSocket: `ws://10.0.2.2:8080/ws/events`

Además, la clase [`ApiConfig.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/network/ApiConfig.kt) permite cambiar la IP del servidor en caliente desde la app con `ApiConfig.setCustomServer(context, apiBaseUrl, wsBaseUrl)`.

### Flujo de Autenticación y Vinculación
1. **Registro / Inicio de Sesión (`LoginActivity` / `RegistroCuentaActivity`)**:
   - Envía `POST /api/v1/auth/login` o `POST /api/v1/auth/register`.
   - Recibe token de sesión, perfil (`CUIDADOR` o `ADULTO_MAYOR`) y `deviceId` asignado.
   - Guarda credenciales en [`SessionManager.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/network/SessionManager.kt).
2. **Vinculación por Código (`VinculacionActivity`)**:
   - **Cuidador**: Solicita código temporal de 6 dígitos mediante `POST /api/v1/auth/pairing/generate`.
   - **Adulto Mayor**: Introduce el código de 6 dígitos con `POST /api/v1/auth/pairing/link`.
   - Ambos dispositivos quedan enlazados para recibir alertas mutuas.
3. **Gestión de Medicamentos (`MedicamentoActivity`)**:
   - Consulta programación médica activa: `GET /api/v1/devices/{deviceId}/schedules`.
   - Alta de medicamento: `POST /api/v1/devices/{deviceId}/schedules`.
   - Edición protegida (solo Cuidador): `PUT /api/v1/devices/{deviceId}/schedules/{scheduleId}`.
   - Borrado lógico preservando historial: `DELETE /api/v1/devices/{deviceId}/schedules/{scheduleId}`.
4. **Alerta y Dispensación (`AvisoDosisActivity`)**:
   - Al tocar "Dispensar", emite comando hacia el ESP32: `POST /api/v1/devices/{deviceId}/command` con acción `DISPENSE_MEDICATION`.
   - Al confirmar la toma del medicamento, actualiza el estado a `TOMADA` con `PATCH /api/v1/devices/{deviceId}/schedules/{scheduleId}/status`.

---

## 4. Conexión del Microcontrolador ESP-32 (Pastillero Inteligente)

### Hardware Recomendado
- Microcontrolador: **ESP32 NodeMCU** (30 o 38 pines).
- Servomotor de compuerta: **SG90** conectado al GPIO 19.
- Avisador acústico / Alarma: **Buzzer** conectado al GPIO 18.
- Pulsador físico de toma confirmada: Conectado al GPIO 5 con resistencia Pull-Up interna.
- Sensor magnético de tapa (Reed Switch): Conectado al GPIO 4.

### Protocolo de Conexión WebSocket
El ESP32 se conecta directamente al WebSocket del servidor mediante la biblioteca `WebSocketsClient.h`:

```text
URL: ws://<IP_O_DOMINIO_SERVIDOR>:8080/ws/events?deviceId=ESP32_MED_01&clientType=ESP32&apiKey=meddibuddy-secret-esp32-key-2026
```

### Tramas JSON Transmitidas por el ESP-32

#### 1. Saludo Inicial (Handshake)
```json
{
  "type": "CONNECT",
  "deviceId": "ESP32_MED_01",
  "clientType": "ESP32",
  "payload": {
    "firmwareVersion": "1.0.0",
    "apiKey": "meddibuddy-secret-esp32-key-2026"
  }
}
```

#### 2. Telemetría Periódica (cada 60 segundos)
```json
{
  "type": "TELEMETRY",
  "deviceId": "ESP32_MED_01",
  "clientType": "ESP32",
  "payload": {
    "battery": 92,
    "pillBoxClosed": true,
    "temperature": 24.2
  }
}
```

#### 3. Evento Físico de Botón Pulsado (Toma Confirmada Físicamente)
Cuando el adulto mayor toma sus pastillas y presiona el botón físico del pastillero:
```json
{
  "type": "ALERT",
  "deviceId": "ESP32_MED_01",
  "clientType": "ESP32",
  "payload": {
    "buttonPressed": true,
    "compartment": 1,
    "alertType": "MEDICATION_TAKEN"
  }
}
```
*El servidor intercepta este evento, calcula la hora con respecto a la ventana de tolerancia de 30 minutos y transiciona automáticamente el medicamento a `TOMADA` o `TARDIA`, notificando inmediatamente a la app del cuidador.*

#### 4. Comando Recibido desde la App Móvil para Dispensar
Cuando el usuario o la app móvil solicita abrir el pastillero:
```json
{
  "type": "COMMAND",
  "deviceId": "SERVER",
  "targetDeviceId": "ESP32_MED_01",
  "payload": {
    "action": "DISPENSE_MEDICATION",
    "compartment": 1
  }
}
```
El ESP32 mueve el servomotor a 90 grados, emite 3 beeps con el buzzer y confirma con un `COMMAND_ACK`:
```json
{
  "type": "COMMAND_ACK",
  "deviceId": "ESP32_MED_01",
  "clientType": "ESP32",
  "payload": {
    "action": "DISPENSE_MEDICATION",
    "status": "SUCCESS"
  }
}
```

### Firmware de Ejemplo para ESP-32
El código listo para cargar en Arduino IDE o PlatformIO se encuentra en:
[`MediBuddy-API-WS/docs/esp32_example.ino`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy-API-WS/docs/esp32_example.ino).

---

## 5. Pruebas y Validación End-to-End

1. **Iniciar la API localmente**:
   ```bash
   cd MediBuddy-API-WS
   ./gradlew bootRun
   ```
2. **Ejecutar el simulador del ESP32 en Python**:
   ```bash
   python3 MediBuddy-API-WS/scripts/simulate_esp32.py
   ```
3. **Ejecutar el simulador de la App Android**:
   ```bash
   python3 MediBuddy-API-WS/scripts/simulate_android.py
   ```
4. **Verificar paso de mensajes**:
   - El simulador de Android enviará el comando `DISPENSE_MEDICATION`.
   - El simulador del ESP-32 responderá con `COMMAND_ACK: SUCCESS`.
   - El simulador del ESP-32 enviará una alerta de botón físico `MEDICATION_TAKEN` y telemetría de batería.
   - Ambos clientes mostrarán la confirmación en consola en tiempo real.
