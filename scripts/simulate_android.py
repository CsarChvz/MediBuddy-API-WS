#!/usr/bin/env python3
"""
Simulador de App Móvil Android para MeddiBudy-Api-Sockets.
Simula la aplicación conectándose por WebSocket, suscribiéndose al dispositivo ESP32,
recibiendo eventos en vivo y permitiendo enviar comandos al hardware.
"""

import asyncio
import json
import websockets
from datetime import datetime, timezone

WS_URL = "ws://localhost:8080/ws/events?deviceId=ANDROID_APP_USER1&clientType=ANDROID"
TARGET_DEVICE = "ESP32_MED_01"

async def android_client():
    print(f"📱 Conectando App Android a: {WS_URL} ...", flush=True)
    async with websockets.connect(WS_URL) as ws:
        print("✅ App Android conectada exitosamente.", flush=True)

        # 1. Enviar registro CONNECT
        connect_msg = {
            "type": "CONNECT",
            "deviceId": "ANDROID_APP_USER1",
            "clientType": "ANDROID",
            "payload": {
                "userId": "usr_48291",
                "os": "Android 14",
                "appVersion": "2.4.0"
            }
        }
        await ws.send(json.dumps(connect_msg))

        # 2. Suscribirse al ESP32
        sub_msg = {
            "type": "SUBSCRIBE",
            "deviceId": "ANDROID_APP_USER1",
            "clientType": "ANDROID",
            "targetDeviceId": TARGET_DEVICE
        }
        await ws.send(json.dumps(sub_msg))
        print(f"🔔 Suscrito a eventos de: {TARGET_DEVICE}", flush=True)

        async def listen_events():
            try:
                async for raw_msg in ws:
                    data = json.loads(raw_msg)
                    msg_type = data.get("type")
                    source = data.get("deviceId")
                    payload = data.get("payload", {})

                    if msg_type == "TELEMETRY":
                        print(f"\n📊 [Android UI] Nueva Telemetría de {source}: {payload}", flush=True)
                    elif msg_type == "ALERT":
                        print(f"\n🚨 [Android UI] ¡ALERTA RECIBIDA de {source}!: {payload}", flush=True)
                    elif msg_type == "COMMAND_ACK":
                        print(f"\n✅ [Android UI] Confirmación recibida: {payload}", flush=True)
                    else:
                        print(f"\nℹ️ [Android UI] Mensaje ({msg_type}): {payload}", flush=True)
            except asyncio.CancelledError:
                pass

        async def send_periodic_command():
            await asyncio.sleep(2)
            # Enviar comando de prueba al ESP32: dispensar dosis
            command_msg = {
                "type": "COMMAND",
                "deviceId": "ANDROID_APP_USER1",
                "clientType": "ANDROID",
                "targetDeviceId": TARGET_DEVICE,
                "payload": {
                    "action": "DISPENSE_MEDICATION",
                    "compartment": 1,
                    "scheduledTime": datetime.now(timezone.utc).isoformat()
                }
            }
            print(f"\n🚀 [Android UI] Enviando comando: DISPENSE_MEDICATION a {TARGET_DEVICE}...", flush=True)
            await ws.send(json.dumps(command_msg))

        listener_task = asyncio.create_task(listen_events())
        command_task = asyncio.create_task(send_periodic_command())

        try:
            await asyncio.gather(listener_task, command_task)
        except KeyboardInterrupt:
            listener_task.cancel()
            command_task.cancel()
            print("\nDesconectando simulador Android.", flush=True)

if __name__ == "__main__":
    asyncio.run(android_client())
