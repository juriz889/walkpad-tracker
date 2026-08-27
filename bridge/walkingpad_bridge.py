#!/usr/bin/env python3
"""
JSON-line bridge between the Java app and a KingSmith WalkingPad over Bluetooth LE.

macOS has no native Java BLE stack (CoreBluetooth is Swift/Obj-C only), so this
small process does the actual BLE work via `ph4-walkingpad` (which uses `bleak`,
a cross-platform BLE library with full macOS/CoreBluetooth support) and exposes
it to the Java side as newline-delimited JSON over stdin/stdout.

Commands (stdin, one JSON object per line):
  {"cmd": "scan", "timeout": 5}
  {"cmd": "connect", "address": "AA:BB:CC:DD:EE:FF"}   // address omitted -> auto-scan
  {"cmd": "status"}                                     // one-shot status request
  {"cmd": "start"}
  {"cmd": "stop"}
  {"cmd": "speed", "value": 3.5}                        // km/h
  {"cmd": "disconnect"}
  {"cmd": "quit"}

Events (stdout, one JSON object per line):
  {"type": "ready"}
  {"type": "scan_result", "devices": [{"name": "...", "address": "..."}]}
  {"type": "status", "distanceKm": 1.23, "steps": 1500, "timeSec": 900,
   "speedKmh": 3.0, "beltState": 1, "manualMode": 0}
  {"type": "ack", "cmd": "start", "ok": true}
  {"type": "error", "cmd": "connect", "message": "..."}
"""

import asyncio
import json
import sys
import threading

from bleak import BleakScanner
from ph4_walkingpad.pad import Controller, WalkingPadCurStatus

POLL_INTERVAL_SEC = 1.0
DEVICE_NAME_HINT = "walkingpad"  # case-insensitive substring matched during auto-scan


def emit(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


class Bridge:
    def __init__(self):
        self.ctrl = None
        self.poll_task = None
        self.connected = False

    def on_status(self, sender, status: WalkingPadCurStatus):
        # Scaling per ph4-walkingpad's WalkingPadCurStatus.__str__: dist/100 -> km,
        # speed/10 -> km/h. steps and time are already plain counts/seconds.
        emit({
            "type": "status",
            "distanceKm": status.dist / 100.0,
            "steps": status.steps,
            "timeSec": status.time,
            "speedKmh": status.speed / 10.0,
            "beltState": status.belt_state,
            "manualMode": status.manual_mode,
        })

    async def poll_loop(self):
        try:
            while self.connected:
                await self.ctrl.ask_stats()
                await asyncio.sleep(POLL_INTERVAL_SEC)
        except asyncio.CancelledError:
            pass

    async def cmd_scan(self, cmd):
        timeout = float(cmd.get("timeout", 5))
        devices = await BleakScanner.discover(timeout=timeout)
        emit({
            "type": "scan_result",
            "devices": [{"name": d.name or "", "address": d.address} for d in devices],
        })

    async def cmd_connect(self, cmd):
        address = cmd.get("address")
        if not address:
            timeout = float(cmd.get("timeout", 5))
            devices = await BleakScanner.discover(timeout=timeout)
            match = next(
                (d for d in devices if d.name and DEVICE_NAME_HINT in d.name.lower()),
                None,
            )
            if not match:
                raise RuntimeError(
                    "No WalkingPad found during auto-scan; pass an explicit address"
                )
            address = match.address

        self.ctrl = Controller()
        self.ctrl.handler_cur_status = self.on_status
        await self.ctrl.run(address)
        self.connected = True
        self.poll_task = asyncio.create_task(self.poll_loop())
        emit({"type": "ack", "cmd": "connect", "ok": True, "address": address})

    async def cmd_disconnect(self, cmd):
        self.connected = False
        if self.poll_task:
            self.poll_task.cancel()
            self.poll_task = None
        if self.ctrl:
            await self.ctrl.disconnect()
            self.ctrl = None
        emit({"type": "ack", "cmd": "disconnect", "ok": True})

    async def cmd_start(self, cmd):
        await self.ctrl.start_belt()
        emit({"type": "ack", "cmd": "start", "ok": True})

    async def cmd_stop(self, cmd):
        await self.ctrl.stop_belt()
        emit({"type": "ack", "cmd": "stop", "ok": True})

    async def cmd_speed(self, cmd):
        kmh = float(cmd["value"])
        await self.ctrl.change_speed(int(round(kmh * 10)))
        emit({"type": "ack", "cmd": "speed", "ok": True})

    async def cmd_status(self, cmd):
        await self.ctrl.ask_stats()
        emit({"type": "ack", "cmd": "status", "ok": True})

    async def dispatch(self, cmd):
        name = cmd.get("cmd")
        handler = {
            "scan": self.cmd_scan,
            "connect": self.cmd_connect,
            "disconnect": self.cmd_disconnect,
            "start": self.cmd_start,
            "stop": self.cmd_stop,
            "speed": self.cmd_speed,
            "status": self.cmd_status,
        }.get(name)
        if handler is None:
            raise RuntimeError(f"Unknown command: {name}")
        await handler(cmd)


def stdin_reader(loop, queue):
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        asyncio.run_coroutine_threadsafe(queue.put(line), loop)


async def main():
    loop = asyncio.get_running_loop()
    queue = asyncio.Queue()
    threading.Thread(target=stdin_reader, args=(loop, queue), daemon=True).start()

    bridge = Bridge()
    emit({"type": "ready"})

    while True:
        line = await queue.get()
        try:
            cmd = json.loads(line)
        except json.JSONDecodeError as e:
            emit({"type": "error", "message": f"invalid JSON: {e}"})
            continue

        if cmd.get("cmd") == "quit":
            if bridge.connected:
                await bridge.cmd_disconnect(cmd)
            break

        try:
            await bridge.dispatch(cmd)
        except Exception as e:
            emit({"type": "error", "cmd": cmd.get("cmd"), "message": str(e)})


if __name__ == "__main__":
    asyncio.run(main())
