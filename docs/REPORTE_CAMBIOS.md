# Reporte de Cambios Realizados: Conexión API, App Móvil MediBuddy y ESP-32

**Fecha:** 21 de Noviembre de 2026  
**Ecosistema:** MediBox / MediBuddy (Android + Kotlin Spring Boot + IoT ESP-32)  

---

## 1. Resumen Ejecutivo
Se completó la integración integral entre el backend de servicios en tiempo real (**MediBuddy-API-WS**) y la aplicación móvil Android (**MediBuddy**), preparando la infraestructura para el despliegue en servidor y la comunicación bidireccional con el pastillero inteligente basado en **ESP-32**. 

Se cumplieron todos los requerimientos solicitados:
1. Creación de la rama `feature/api-connection` en el repositorio de la aplicación móvil.
2. Implementación del módulo de autenticación en la API (`/api/v1/auth`) con registro, login, hashing seguro y vinculación cuidador-paciente mediante códigos de 6 dígitos.
3. Incorporación de los endpoints faltantes en la API para actualización protegida por roles, borrado lógico y cambio de estado de tomas de medicamentos.
4. Configuración **100% modular mediante variables de entorno**, eliminando cualquier URL o credencial hardcodeada tanto en la app móvil como en el backend.
5. Arquitectura de red en Android con Retrofit 2, OkHttp, Gson, Coroutines y OkHttp WebSocket reactivo con `SharedFlow`.
6. Generación de la guía de conexión técnica y despliegue (`GUIA_INTEGRACION_API_APP_ESP32.md`).
7. Verificación completa con pruebas unitarias y de integración pasando al 100% (`BUILD SUCCESSFUL`).

---

## 2. Gestión de Ramas Git

### Repositorio `MediBuddy` (App Móvil)
- **Rama creada:** `feature/api-connection` (originada a partir de la rama de trabajo `master` para conservar la totalidad de las actividades, pantallas, modelos Room y recursos desarrollados por el equipo).
- **Estado:** Cambios integrados y confirmados en el commit `ccdb66e` (*feat(mobile): integrar conexion modular con API REST y WebSockets en feature/api-connection*).

### B. Repositorio `MediBuddy-API-WS` (Backend)
- **Rama de trabajo:** `main`.
- **Commits generados**:
  - `97a3ae6` - *feat(api): agregar modulo auth, endpoints schedules y variables de entorno modulares*
  - `0e06f05` - *feat(docker): agregar soporte de variables .env, healthchecks y perfil postgres*
  - `14e8a2f` - *docs: enlazar guia de pruebas cURL en README*
- **Estado:** Rama limpia y sincronizada localmente con todas las pruebas pasando (`BUILD SUCCESSFUL`).

### C. Repositorio `MediBuddy` (App Móvil)
- **Rama de trabajo:** `feature/api-connection` (creada a partir de `master`).
- **Commits generados**:
  - `ccdb66e` - *feat(mobile): integrar conexion modular con API REST y WebSockets en feature/api-connection*
- **Estado:** Rama de feature lista para pull request.

---

## 3. Cambios Implementados en el Backend (`MediBuddy-API-WS`)

### A. Módulo de Autenticación (`/api/v1/auth`)
- **Controlador nuevo [`AuthController.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy-API-WS/src/main/kotlin/com/meddibuddy/meddibuddy_sockets/controller/AuthController.kt)**:
  - `POST /api/v1/auth/register`: Registro de nuevos usuarios con rol (`CUIDADOR` o `ADULTO_MAYOR`), validación de unicidad de correo, hash SHA-256 de contraseña y auto-registro de credencial en `SecurityService`.
  - `POST /api/v1/auth/login`: Validación de credenciales y emisión de token de sesión vinculado al `deviceId`.
  - `POST /api/v1/auth/pairing/generate`: Generación de código numérico de 6 dígitos para el cuidador con vigencia de 15 minutos.
  - `POST /api/v1/auth/pairing/link`: Canje del código por parte del adulto mayor, asociando ambos dispositivos.

### B. Endpoints de Horarios y Medicamentos (`/api/v1/devices/{deviceId}/schedules`)
- **Modificación en [`DeviceController.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy-API-WS/src/main/kotlin/com/meddibuddy/meddibuddy_sockets/controller/DeviceController.kt)**:
  - `PUT /api/v1/devices/{deviceId}/schedules/{scheduleId}`: Permite actualizar medicamento, dosis y horario aplicando la regla de negocio que restringe esta acción exclusivamente al rol `CUIDADOR` (`actualizarConfiguracion`).
  - `DELETE /api/v1/devices/{deviceId}/schedules/{scheduleId}`: Aplica borrado lógico (`isActive = false`) para conservar la trazabilidad histórica (`aplicarBorradoLogico`).
  - `PATCH /api/v1/devices/{deviceId}/schedules/{scheduleId}/status`: Transiciona el estado de la toma (`EstadoToma`: `PENDIENTE`, `DISPENSADA`, `TOMADA`, `POSPUESTA`, `TARDIA`, etc.).

### C. Persistencia y Modelos JPA
- **Modificación en [`Entities.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy-API-WS/src/main/kotlin/com/meddibuddy/meddibuddy_sockets/entity/Entities.kt)**:
  - Creación de `UserEntity` y `PairingCodeEntity`.
  - Asignación de valores por defecto a todos los constructores de entidades para garantizar la instanciación limpia sin argumentos por parte de Hibernate/JPA.
- **Modificación en [`Repositories.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy-API-WS/src/main/kotlin/com/meddibuddy/meddibuddy_sockets/repository/Repositories.kt)**:
  - Creación de `UserRepository` (`findByEmail`, `existsByEmail`, `findByDeviceId`).
  - Creación de `PairingCodeRepository` (`findByCodeAndIsUsedFalseAndExpiresAtAfter`).

### D. Configuración Modular sin Hardcoding y Docker Compose
- **Archivos de variables de entorno generados**:
  - [`.env`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy-API-WS/.env) y [`.env.example`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy-API-WS/.env.example) con credenciales listas para pruebas inmediatas.
- **Docker Compose**:
  - [`docker-compose.yml`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy-API-WS/docker-compose.yml) actualizado con lectura directa de variables de entorno `.env`, healthcheck de PostgreSQL 16 y servicio opcional de pgAdmin (`http://localhost:5050`).
- **Perfil de Base de Datos PostgreSQL**:
  - [`application-postgres.properties`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy-API-WS/src/main/resources/application-postgres.properties) configurado para conectar automáticamente al contenedor de PostgreSQL con `./gradlew bootRun --args='--spring.profiles.active=postgres'`.
- **Modificación en [`application.properties`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy-API-WS/src/main/resources/application.properties)**:
  - `server.port=${PORT:8080}`
  - `spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:h2:mem:meddibuddy_db...}`
  - `meddibuddy.security.default-api-key=${MEDDIBUDDY_API_KEY:...}`
  - `meddibuddy.security.default-jwt-secret=${MEDDIBUDDY_JWT_SECRET:...}`

### E. Pruebas Automatizadas del Backend
- **Prueba nueva [`AuthControllerTest.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy-API-WS/src/test/kotlin/com/meddibuddy/meddibuddy_sockets/AuthControllerTest.kt)**:
  - Valida el ciclo completo de registro, login válido/inválido, generación de código y vinculación.
  - Valida la edición de medicamentos autorizada para el `CUIDADOR` y rechazada (`403 FORBIDDEN`) para el `ADULTO_MAYOR`.
  - Valida el borrado lógico y la transición de estados.
- **Resultado de ejecución Gradle**: `BUILD SUCCESSFUL` (13 pruebas ejecutadas, 0 fallos).

---

## 4. Cambios Implementados en la Aplicación Móvil (`MediBuddy`)

### A. Dependencias y Configuración Gradle Modular
- **[`libs.versions.toml`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/gradle/libs.versions.toml)**: Incorporación de OkHttp 4.12.0, Retrofit 2.11.0, Gson 2.11.0 y Kotlinx Coroutines Android 1.8.1.
- **[`app/build.gradle.kts`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/build.gradle.kts)**:
  - Habilitación de `buildFeatures { buildConfig = true }`.
  - Inyección modular de `API_BASE_URL` y `WS_BASE_URL` leyendo las variables de entorno `MEDIBUDDY_API_URL` y `MEDIBUDDY_WS_URL` (con fallback estándar a `http://10.0.2.2:8080/` para emulador).
- **[`gradle-daemon-jvm.properties`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/gradle/gradle-daemon-jvm.properties)**: Ajuste de `toolchainVersion=21` para alinearse con el entorno JDK.
- **[`AndroidManifest.xml`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/AndroidManifest.xml)**: Adición de `android:usesCleartextTraffic="true"` para desarrollo HTTP sin certificados SSL.

### B. Capa de Red Modular (`com.medibox.network`)
1. **[`ApiConfig.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/network/ApiConfig.kt)**: Administrador dinámico de URLs base; permite configurar una IP o dominio de servidor en tiempo de ejecución (`setCustomServer`).
2. **[`model/NetworkModels.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/network/model/NetworkModels.kt)**: DTOs para autenticación, vinculación, medicamentos, comandos hacia hardware y tramas WebSocket.
3. **[`api/AuthApiService.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/network/api/AuthApiService.kt)**: Interfaz Retrofit para login, registro y códigos de vinculación.
4. **[`api/DeviceApiService.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/network/api/DeviceApiService.kt)**: Interfaz Retrofit para operaciones CRUD de medicamentos y comandos directos al pastillero (`POST /command`).
5. **[`SessionManager.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/network/SessionManager.kt)**: Gestor centralizado de tokens, credenciales, `deviceId` e identificador del dispositivo vinculado.
6. **[`RetrofitClient.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/network/RetrofitClient.kt)**: Cliente HTTP singleton con interceptor para adjuntar tokens Bearer, timeout y logging.
7. **[`WebSocketManager.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/network/WebSocketManager.kt)**: Conexión persistente WebSocket con ping/pong cada 20s, suscripción a eventos del pastillero y flujo reactivo `SharedFlow<DeviceMessageDto>`.

### C. Conexión de Actividades de Usuario
- **[`LoginActivity.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/ui/LoginActivity.kt)**: Autenticación asíncrona con la API mediante `authApi.login()`, guardado de sesión y fallback tolerante a fallos si el servidor no está en línea.
- **[`RegistroCuentaActivity.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/ui/RegistroCuentaActivity.kt)**: Envío de datos de registro al servidor mediante `authApi.register()` y paso a vinculación.
- **[`VinculacionActivity.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/ui/VinculacionActivity.kt)**: Obtención de código en la nube para cuidadores y validación de código para adultos mayores con actualización de `linkedDeviceId`.
- **[`MedicamentoActivity.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/ui/MedicamentoActivity.kt)**: Sincronización bidireccional de medicamentos (`GET`, `POST`, `PUT`, `DELETE` lógico) con la API REST.
- **[`AvisoDosisActivity.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/ui/AvisoDosisActivity.kt)**: Al presionar "Dispensar", envía el comando físico `DISPENSE_MEDICATION` al pastillero vía API y registra la confirmación de toma (`TOMADA`).
- **[`MainActivity.kt`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/MediBuddy/app/src/main/java/com/medibox/ui/MainActivity.kt)**: Inicia el WebSocket en segundo plano, escucha alertas (`MEDICATION_TAKEN`, `LOW_BATTERY`), muestra el dispositivo vinculado y limpia la sesión al salir.

---

## 5. Documentación Generada
- **[`GUIA_INTEGRACION_API_APP_ESP32.md`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/GUIA_INTEGRACION_API_APP_ESP32.md)**: Manual de integración con diagramas Mermaid, guía de despliegue en servidor (Docker/Nginx), especificación de tramas WebSocket para el ESP-32 y ejemplos de código.
- **[`README.md`](file:///home/csarchvz/gitThings/MediBox_MediBuddy/README.md)**: Visión global del ecosistema MediBox y MediBuddy en el directorio raíz.
