#!/usr/bin/env python3
"""
Simulador de ESP32 para MeddiBudy-Api-Sockets.
Simula el comportamiento de un dispositivo físico enviando telemetría periódica
y reaccionando a comandos (ej. encender zumbador, dispensar medicamento).
"""

import asyncio
import json
import random
import websockets
from datetime import datetime, timezone

WS_URL = "ws://localhost:8080/ws/events?deviceId=ESP32_MED_01&clientType=ESP32"

async def esp32_client():
    print(f"🔌 Conectando ESP32 a: {WS_URL} ...", flush=True)
    async with websockets.connect(WS_URL) as ws:
        print("✅ ESP32 conectado exitosamente.", flush=True)

        # Enviar mensaje de registro CONNECT
        connect_msg = {
            "type": "CONNECT",
            "deviceId": "ESP32_MED_01",
            "clientType": "ESP32",
            "payload": {
                "firmwareVersion": "1.0.0",
                "hardware": "ESP32-WROOM-32",
                "macAddress": "24:6F:28:AB:CD:EF"
            }
        }
        await ws.send(json.dumps(connect_msg))

        async def listen_commands():
            try:
                async for raw_msg in ws:
                    data = json.loads(raw_msg)
                    msg_type = data.get("type")
                    if msg_type == "COMMAND":
                        payload = data.get("payload", {})
                        action = payload.get("action")
                        print(f"\n⚙️ [ESP32] COMANDO RECIBIDO: {action} (params: {payload})", flush=True)

                        # Simular ejecución de hardware (ej. motor, buzzer)
                        await asyncio.sleep(1)

                        # Enviar confirmación (COMMAND_ACK)
                        ack_msg = {
                            "type": "COMMAND_ACK",
                            "deviceId": "ESP32_MED_01",
                            "clientType": "ESP32",
                            "targetDeviceId": data.get("deviceId"),
                            "payload": {
                                "executedAction": action,
                                "status": "SUCCESS",
                                "detail": f"Acción {action} completada físicamente en ESP32"
                            }
                        }
                        await ws.send(json.dumps(ack_msg))
                        print(f"✅ [ESP32] ACK enviado para acción: {action}", flush=True)
                    elif msg_type == "COMMAND_ACK":
                        print(f"ℹ️ [ESP32] Server ACK: {data.get('payload')}", flush=True)
            except asyncio.CancelledError:
                pass

        async def send_telemetry_loop():
            battery = 100
            while True:
                await asyncio.sleep(3)
                battery = max(10, battery - random.randint(1, 3))
                telemetry_msg = {
                    "type": "TELEMETRY",
                    "deviceId": "ESP32_MED_01",
                    "clientType": "ESP32",
                    "payload": {
                        "battery": battery,
                        "pillBoxClosed": True,
                        "temperature": round(23.5 + random.uniform(-0.5, 0.5), 1),
                        "wifiRssi": random.randint(-65, -50),
                        "timestamp": datetime.now(timezone.utc).isoformat()
                    }
                }
                await ws.send(json.dumps(telemetry_msg))
                print(f"📡 [ESP32] Telemetría enviada: Batería={battery}%", flush=True)

        listener_task = asyncio.create_task(listen_commands())
        telemetry_task = asyncio.create_task(send_telemetry_loop())

        try:
            await asyncio.gather(listener_task, telemetry_task)
        except KeyboardInterrupt:
            listener_task.cancel()
            telemetry_task.cancel()
            print("\nDesconectando simulador ESP32.")

if __name__ == "__main__":
    asyncio.run(esp32_client())
