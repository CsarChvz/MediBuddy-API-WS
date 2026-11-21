# Guía de Pruebas con cURL: API MediBuddy

Este archivo contiene los comandos `curl` listos para copiar y pegar para probar el ciclo completo de la API (Autenticación, Vinculación Cuidador-Adulto Mayor, CRUD de Medicamentos, Comandos IoT y Transiciones de Estado).

---

## 🚀 Preparación: Levantar la Base de Datos y la API

### 1. Iniciar Base de Datos en Docker
En la carpeta `MediBuddy-API-WS`:
```bash
docker compose up -d
```

### 2. Iniciar la API Spring Boot
```bash
./gradlew bootRun --args='--spring.profiles.active=postgres'
```
*(Si no deseas usar Docker, ejecuta simplemente `./gradlew bootRun` para usar la base de datos H2 en memoria).*

---

## 🧪 Catálogo de Comandos cURL

### 1. Registro de Usuario Cuidador
Crea una cuenta para el rol `CUIDADOR`:
```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Laura Cuidadora",
    "email": "cuidadora@test.com",
    "password": "password123",
    "role": "CUIDADOR"
  }'
```
**Respuesta esperada (201 Created):**
```json
{
  "token": "mb_tok_1_...",
  "userId": 1,
  "name": "Laura Cuidadora",
  "email": "cuidadora@test.com",
  "role": "CUIDADOR",
  "deviceId": "USER_CUIDADOR_A1B2C3D4",
  "linkedDeviceId": null
}
```

---

### 2. Registro de Usuario Adulto Mayor (Paciente)
Crea una cuenta para el rol `ADULTO_MAYOR` asignándole el identificador de su pastillero `ESP32_MED_01`:
```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Don Jose",
    "email": "jose@test.com",
    "password": "password123",
    "role": "ADULTO_MAYOR",
    "customDeviceId": "ESP32_MED_01"
  }'
```

---

### 3. Iniciar Sesión (Login)
Valida credenciales y devuelve el token de sesión:
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "cuidadora@test.com",
    "password": "password123"
  }'
```

---

### 4. Generar Código de Vinculación de 6 Dígitos (Cuidador)
Genera un código temporal válido por 15 minutos para que el paciente lo ingrese en su app:
```bash
curl -X POST http://localhost:8080/api/v1/auth/pairing/generate \
  -H "Content-Type: application/json" \
  -d '{
    "caregiverUserId": 1,
    "caregiverDeviceId": "USER_CUIDADOR_A1B2C3D4"
  }'
```
**Respuesta esperada:**
```json
{
  "code": "481923",
  "expiresAt": "2026-11-21T23:35:00Z",
  "caregiverDeviceId": "USER_CUIDADOR_A1B2C3D4"
}
```

---

### 5. Vincular al Adulto Mayor con el Código de 6 Dígitos
*(Reemplaza `481923` por el código obtenido en el paso 4)*:
```bash
curl -X POST http://localhost:8080/api/v1/auth/pairing/link \
  -H "Content-Type: application/json" \
  -d '{
    "code": "435644",
    "patientUserId": 2,
    "patientDeviceId": "ESP32_MED_01"
  }'
```
**Respuesta esperada:**
```json
{
  "success": true,
  "message": "Vinculación exitosa.",
  "caregiverDeviceId": "USER_CUIDADOR_A1B2C3D4",
  "patientDeviceId": "ESP32_MED_01"
}
```

---

### 6. Programar un Nuevo Medicamento (Cuidador)
Registra un horario para el pastillero `ESP32_MED_01`:
```bash
curl -X POST http://localhost:8080/api/v1/devices/ESP32_MED_01/schedules \
  -H "Content-Type: application/json" \
  -d '{
    "medicationName": "Losartán 50mg",
    "dose": "1 tableta",
    "compartment": 1,
    "scheduledHour": 20,
    "scheduledMinute": 0
  }'
```

---

### 7. Consultar Horarios y Medicamentos Activos del Dispositivo
```bash
curl -X GET http://localhost:8080/api/v1/devices/ESP32_MED_01/schedules
```

---

### 8. Editar Medicamento (Validación de Rol Cuidador)
Actualiza el nombre, dosis y hora (solo permitido para `CUIDADOR`):
```bash
curl -X PUT http://localhost:8080/api/v1/devices/ESP32_MED_01/schedules/1 \
  -H "Content-Type: application/json" \
  -d '{
    "medicationName": "Losartán 100mg",
    "dose": "2 tabletas",
    "scheduledHour": 21,
    "scheduledMinute": 30,
    "clientType": "CUIDADOR"
  }'
```
> **Nota de Seguridad**: Si envías `"clientType": "ADULTO_MAYOR"`, la API devolverá un error `403 Forbidden` protegiendo la regla de negocio.

---

### 9. Simular Botón "Dispensar" de la App Móvil (Comando al ESP-32)
Envía la orden física al pastillero inteligente:
```bash
curl -X POST http://localhost:8080/api/v1/devices/ESP32_MED_01/command \
  -H "Content-Type: application/json" \
  -d '{
    "action": "DISPENSE_MEDICATION",
    "params": {
      "compartment": 1
    }
  }'
```
**Respuesta esperada:**
```json
{
  "status": "SENT",
  "deviceId": "ESP32_MED_01",
  "action": "DISPENSE_MEDICATION"
}
```

---

### 10. Confirmar Dosis Tomada (Cambio de Estado)
Transiciona el estado del ciclo de la dosis a `TOMADA` (o `POSPUESTA`):
```bash
curl -X PATCH http://localhost:8080/api/v1/devices/ESP32_MED_01/schedules/1/status \
  -H "Content-Type: application/json" \
  -d '{
    "estadoToma": "TOMADA"
  }'
```

---

### 11. Borrado Lógico de Medicamento (Conserva Historial)
Desactiva el medicamento (`isActive = false`) sin eliminarlo físicamente:
```bash
curl -X DELETE "http://localhost:8080/api/v1/devices/ESP32_MED_01/schedules/1?clientType=CUIDADOR"
```
**Respuesta esperada:**
```json
{
  "status": "DELETED",
  "scheduleId": 1,
  "isActive": false
}
```

---

### 12. Consultar Historial de Auditoría de Comandos
Muestra todas las órdenes enviadas al pastillero y su estado (`SENT`, `EXECUTED`, etc.):
```bash
curl -X GET http://localhost:8080/api/v1/devices/ESP32_MED_01/commands?limit=10
```
