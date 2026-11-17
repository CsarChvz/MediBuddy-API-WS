# Actividad 2: Programar la Lógica de Negocio del Modelo POO

## 1. Contexto y Objetivos

En el sistema **MediBuddy** (Pastillero Inteligente IoT y Aplicación Móvil con WebSockets y Spring Boot), la **Actividad 2** tiene como objetivo central implementar y blindar las reglas del dominio dentro de las entidades del modelo POO (Programación Orientada a Objetos) y propagar su funcionamiento hacia los servicios y manejadores de WebSockets.

Con esto, el equipo de frontend (móvil/web) y aseguramiento de calidad (QA) disponen de un flujo robusto, consistente y validado contra accesos indebidos o inconsistencias en los estados de dispensación y consumo de medicamentos.

---

## 2. Reglas de Negocio Implementadas

### Regla 1: Estados de la Toma (`EstadoToma`)
Se implementó un enum estricto con los **7 estados exactos** que representan el ciclo de vida de cada toma:
- `PENDIENTE`: Toma programada aún no llegada a su hora o en espera.
- `DISPENSADA`: El pastillero mecánico liberó el medicamento en el compartimento.
- `TOMADA`: El usuario ingirió el medicamento dentro del margen de tiempo esperado.
- `POSPUESTA`: El usuario o cuidador postergó la alarma de toma.
- `NO_CONFIRMADA`: Se venció la ventana de tiempo sin confirmación del sensor o botón.
- `TARDIA`: El usuario presionó el botón o tomó la dosis después del tiempo de tolerancia.
- `ERROR`: Falla de hardware, atasco o anomalía en el compartimento.

### Regla 2: Configuración Protegida (Control de Roles POO)
Solo los clientes con rol **`CUIDADOR`** (identificados mediante el campo `clientType` en `DeviceEntity` o en el mensaje autenticado) tienen privilegios para modificar los campos de configuración:
- Nombre del medicamento (`medicationName`)
- Dosis (`dose`)
- Hora y minutos programados (`scheduledHour`, `scheduledMinute`)

> **Seguridad**: Si un usuario con el rol de **`ADULTO_MAYOR`** (o cualquier rol distinto de `CUIDADOR`) intenta alterar esta configuración, la entidad lanza de manera inmediata una excepción de tipo `java.lang.SecurityException`.

### Regla 3: Borrado Lógico
Los medicamentos **nunca se eliminan con una sentencia `DELETE` física de SQL**. En su lugar:
- Se apaga una bandera booleana (`isActive = false`) dentro de la entidad `MedicationScheduleEntity`.
- Esto permite conservar todo el historial de tomas, adherencia y trazabilidad médica.
- Al igual que la configuración, **únicamente el `CUIDADOR`** está autorizado para ejecutar este borrado lógico; un intento por parte de un `ADULTO_MAYOR` arroja una `SecurityException`.

---

## 3. Resumen de Archivos Modificados y Creados

| Archivo | Ubicación | Descripción de Cambios |
| :--- | :--- | :--- |
| **`Entities.kt`** | `src/main/kotlin/.../entity/` | Creación del enum `EstadoToma`, atributos mutables `var` en `MedicationScheduleEntity`, métodos POO encapsulados (`actualizarConfiguracion`, `aplicarBorradoLogico`, `registrarTomaFisica`, `transicionarEstado`). |
| **`EventModels.kt`** | `src/main/kotlin/.../model/` | Definición de DTOs para configuración de medicamentos, borrado lógico, reporte de estados y eventos físicos de botón (`UpdateMedicationConfigPayload`, `DeleteMedicationPayload`, etc.). |
| **`Repositories.kt`** | `src/main/kotlin/.../repository/` | Incorporación del método `findByDeviceIdAndCompartmentAndIsActiveTrue` en `MedicationScheduleRepository`. |
| **`EventDispatcherService.kt`** | `src/main/kotlin/.../service/` | Lógica de servicio para orquestar la invocación de métodos de la entidad, captura y manejo de `SecurityException` (alertas WebSocket `FORBIDDEN`), y propagación a clientes. |
| **`DeviceWebSocketHandler.kt`** | `src/main/kotlin/.../handler/` | Interceptor de mensajes entrantes para eventos físicos de botón (`BUTTON_PRESSED`), evaluando en tiempo real si la toma es `TOMADA` o `TARDIA`. |
| **`MedicationBusinessRulesTest.kt`** | `src/test/kotlin/.../` | Suite de pruebas unitarias automatizadas para verificar las 3 reglas de negocio y transiciones temporales. |

---

## 4. ¿Cómo Funciona la Lógica Implementada?

### 4.1. Encapsulamiento en la Entidad (`MedicationScheduleEntity`)
La entidad no es una estructura de datos pasiva ("anémica"), sino que contiene la lógica de negocio y las validaciones de acceso:

```kotlin
fun actualizarConfiguracion(clientType: String, nuevoNombre: String, nuevaDosis: String, nuevaHora: Int, nuevoMinuto: Int) {
    if (clientType.equals("ADULTO_MAYOR", ignoreCase = true) || !clientType.equals("CUIDADOR", ignoreCase = true)) {
        throw SecurityException("Acceso denegado: Solo el usuario con rol 'CUIDADOR' puede modificar la configuración del medicamento.")
    }
    // Validaciones de dominio
    require(nuevoNombre.isNotBlank())
    require(nuevaDosis.isNotBlank())
    require(nuevaHora in 0..23)
    require(nuevoMinuto in 0..59)

    this.medicationName = nuevoNombre
    this.dose = nuevaDosis
    this.scheduledHour = nuevaHora
    this.scheduledMinute = nuevoMinuto
}
```

### 4.2. Flujo de Configuración Protegida y Manejo de Excepciones
1. Llega un mensaje por WebSocket o API con tipo `MEDICATION_CONFIG` o comando `UPDATE_MEDICATION`.
2. `EventDispatcherService` identifica el `clientType` del emisor.
3. Se invoca `schedule.actualizarConfiguracion(clientType, ...)`.
4. **Si el emisor es `ADULTO_MAYOR`**:
   - Se captura la `SecurityException`.
   - Se emite una alerta WebSocket inmediata al cliente con código `SECURITY_VIOLATION` y estado `FORBIDDEN`.
   - La base de datos no se modifica.
5. **Si el emisor es `CUIDADOR`**:
   - Los datos se actualizan y se persisten en base de datos.
   - Se reenvía la notificación a los suscriptores (móvil del adulto mayor y pastillero ESP32).

### 4.3. Flujo del Interceptor de Botón Físico (`TOMADA` vs `TARDIA`)
Cuando el adulto mayor toma su dosis y presiona el botón físico del pastillero inteligente (ESP32):
1. El ESP32 envía un frame WebSocket con payload de botón presionado (`BUTTON_PRESSED` o `buttonPressed: true`).
2. El interceptor en `DeviceWebSocketHandler` detecta el evento en tiempo de recepción.
3. Se calcula la diferencia entre la hora actual (`LocalTime.now()`) y la hora programada del medicamento en ese compartimento:
   - **Dentro de la ventana de tolerancia** (por defecto 30 minutos tras la hora programada): el estado transiciona a **`TOMADA`**.
   - **Posterior a la ventana de tolerancia**: el estado transiciona a **`TARDIA`**.
4. El nuevo estado queda almacenado en JPA y se emite un broadcast a la aplicación móvil del cuidador.

---

## 5. Ejemplos de Payloads para Integración (Frontend y QA)

### Modificar Medicamento (CUIDADOR - Exitoso)
**Envío WebSocket:**
```json
{
  "type": "MEDICATION_CONFIG",
  "deviceId": "APP_CUIDADOR_01",
  "clientType": "CUIDADOR",
  "payload": {
    "scheduleId": 1,
    "medicationName": "Losartán",
    "dose": "50mg",
    "scheduledHour": 9,
    "scheduledMinute": 0
  }
}
```
**Respuesta del Servidor:**
```json
{
  "type": "COMMAND_ACK",
  "deviceId": "SERVER",
  "payload": {
    "status": "SUCCESS",
    "scheduleId": 1,
    "medicationName": "Losartán",
    "dose": "50mg",
    "scheduledHour": 9,
    "scheduledMinute": 0
  }
}
```

### Intento por ADULTO_MAYOR (Rechazado por Seguridad)
**Envío WebSocket:**
```json
{
  "type": "MEDICATION_CONFIG",
  "deviceId": "APP_ADULTO_MAYOR_01",
  "clientType": "ADULTO_MAYOR",
  "payload": {
    "scheduleId": 1,
    "medicationName": "Aspirina",
    "dose": "100mg",
    "scheduledHour": 10,
    "scheduledMinute": 0
  }
}
```
**Respuesta del Servidor:**
```json
{
  "type": "ALERT",
  "deviceId": "SERVER",
  "payload": {
    "status": "FORBIDDEN",
    "errorCode": "SECURITY_VIOLATION",
    "error": "Acceso denegado: Solo el usuario con rol 'CUIDADOR' puede modificar la configuración del medicamento. El rol 'ADULTO_MAYOR' no tiene permisos."
  }
}
```

### Borrado Lógico (CUIDADOR)
**Envío WebSocket:**
```json
{
  "type": "MEDICATION_DELETE",
  "deviceId": "APP_CUIDADOR_01",
  "clientType": "CUIDADOR",
  "payload": {
    "scheduleId": 1
  }
}
```
**Efecto:** El registro permanece en base de datos con `is_active = false`.

### Evento Botón Físico Presionado (ESP32)
**Envío WebSocket:**
```json
{
  "type": "TELEMETRY",
  "deviceId": "ESP32_PATIENT_1",
  "clientType": "ESP32",
  "payload": {
    "buttonPressed": true,
    "compartment": 1
  }
}
```
**Notificación recibida por la App Móvil del Cuidador:**
```json
{
  "type": "ALERT",
  "deviceId": "ESP32_PATIENT_1",
  "payload": {
    "alertType": "MEDICATION_TAKEN",
    "scheduleId": 1,
    "medicationName": "Losartán",
    "compartment": 1,
    "estadoToma": "TOMADA",
    "horaRegistro": "09:12:35"
  }
}
```

---

## 6. Verificación y Calidad (QA)

Todas las reglas de negocio han sido verificadas ejecutando el comando de Gradle:
```bash
./gradlew test
```
**Resultado:** `BUILD SUCCESSFUL` con 100% de pruebas unitarias y de integración superadas.
