/*
  ==============================================================
  Ejemplo de Firmware para ESP32 (Arduino IDE / PlatformIO)
  Librerías requeridas:
    - WebSockets by Markus Sattler (https://github.com/Links2004/arduinoWebSockets)
    - ArduinoJson by Benoît Blanchon (versión 6 o 7)
    - WiFi.h (incluida en el core de ESP32)
  ==============================================================
*/

#include <WiFi.h>
#include <WebSocketsClient.h>
#include <ArduinoJson.h>

const char* SSID = "TU_RED_WIFI";
const char* PASSWORD = "TU_PASSWORD_WIFI";

// Host del servidor (la IP de tu computadora en la red local)
const char* WS_HOST = "192.168.1.100";
const int WS_PORT = 8080;
const char* WS_PATH = "/ws/events?deviceId=ESP32_MED_01&clientType=ESP32";

WebSocketsClient webSocket;
unsigned long lastTelemetryTime = 0;
const unsigned long TELEMETRY_INTERVAL = 10000; // Cada 10 segundos

// Pines de hardware (ejemplos)
const int PIN_BUZZER = 18;
const int PIN_SERVO = 19;
const int PIN_BUTTON_EMERGENCY = 4;

void sendCommandAck(const char* action, const char* status) {
  StaticJsonDocument<256> doc;
  doc["type"] = "COMMAND_ACK";
  doc["deviceId"] = "ESP32_MED_01";
  doc["clientType"] = "ESP32";
  
  JsonObject payload = doc.createNestedObject("payload");
  payload["action"] = action;
  payload["status"] = status;

  String output;
  serializeJson(doc, output);
  webSocket.sendTXT(output);
}

void handleCommand(JsonObject payload) {
  const char* action = payload["action"];
  Serial.printf("⚙️ Ejecutando comando en hardware: %s\n", action);

  if (strcmp(action, "DISPENSE_MEDICATION") == 0) {
    // Código para mover el servomotor / dispensador
    digitalWrite(PIN_BUZZER, HIGH);
    delay(200);
    digitalWrite(PIN_BUZZER, LOW);
    
    sendCommandAck("DISPENSE_MEDICATION", "SUCCESS");
  } 
  else if (strcmp(action, "BUZZER_ON") == 0) {
    digitalWrite(PIN_BUZZER, HIGH);
    delay(500);
    digitalWrite(PIN_BUZZER, LOW);
    sendCommandAck("BUZZER_ON", "SUCCESS");
  } 
  else {
    sendCommandAck(action, "UNKNOWN_ACTION");
  }
}

void webSocketEvent(WStype_t type, uint8_t * payload, size_t length) {
  switch(type) {
    case WStype_DISCONNECTED:
      Serial.println("[WS] Desconectado del servidor");
      break;
    case WStype_CONNECTED:
      Serial.println("[WS] Conectado exitosamente al servidor");
      break;
    case WStype_TEXT: {
      Serial.printf("[WS] Mensaje recibido: %s\n", payload);
      StaticJsonDocument<512> doc;
      DeserializationError error = deserializeJson(doc, payload, length);
      if (error) {
        Serial.print("Error al parsear JSON: ");
        Serial.println(error.c_str());
        return;
      }

      const char* msgType = doc["type"];
      if (strcmp(msgType, "COMMAND") == 0) {
        handleCommand(doc["payload"]);
      }
      break;
    }
    case WStype_BIN:
    case WStype_ERROR:
    case WStype_PING:
    case WStype_PONG:
      break;
  }
}

void sendTelemetry() {
  StaticJsonDocument<256> doc;
  doc["type"] = "TELEMETRY";
  doc["deviceId"] = "ESP32_MED_01";
  doc["clientType"] = "ESP32";

  JsonObject payload = doc.createNestedObject("payload");
  payload["battery"] = 88; // Medir pin ADC de batería
  payload["pillBoxClosed"] = true; // Medir sensor magnético/switch
  payload["wifiRssi"] = WiFi.RSSI();

  String output;
  serializeJson(doc, output);
  webSocket.sendTXT(output);
  Serial.println("📡 Telemetría enviada");
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_BUZZER, OUTPUT);
  pinMode(PIN_BUTTON_EMERGENCY, INPUT_PULLUP);

  // Conectar WiFi
  WiFi.begin(SSID, PASSWORD);
  Serial.print("Conectando a WiFi...");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.printf("\nWiFi Conectado. IP local: %s\n", WiFi.localIP().toString().c_str());

  // Iniciar cliente WebSocket
  webSocket.begin(WS_HOST, WS_PORT, WS_PATH);
  webSocket.onEvent(webSocketEvent);
  webSocket.setReconnectInterval(5000); // Reconexión automática cada 5s si se cae WiFi
}

void loop() {
  webSocket.loop();

  // Enviar telemetría periódica
  if (millis() - lastTelemetryTime > TELEMETRY_INTERVAL) {
    lastTelemetryTime = millis();
    sendTelemetry();
  }

  // Detectar botón de pánico físico (ejemplo)
  if (digitalRead(PIN_BUTTON_EMERGENCY) == LOW) {
    StaticJsonDocument<256> doc;
    doc["type"] = "ALERT";
    doc["deviceId"] = "ESP32_MED_01";
    doc["clientType"] = "ESP32";
    JsonObject payload = doc.createNestedObject("payload");
    payload["alert"] = "PANIC_BUTTON_PRESSED";
    payload["severity"] = "HIGH";

    String output;
    serializeJson(doc, output);
    webSocket.sendTXT(output);
    delay(1000); // Debounce
  }
}
