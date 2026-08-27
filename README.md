# WalkingPad

Connects to a KingSmith WalkingPad over Bluetooth LE and shows live steps,
distance, speed and time in a browser UI.

## Architecture

```
React + TypeScript UI  --HTTP/SSE-->  Spring Boot backend  --stdin/stdout JSON-->  Python bridge (bleak)  --BLE-->  WalkingPad
```

macOS has no native Java BLE stack (CoreBluetooth is Swift/Obj-C only), so the
actual Bluetooth work is done by a small Python process using
[`ph4-walkingpad`](https://github.com/ph4r05/ph4-walkingpad) (built on
[`bleak`](https://github.com/hbldh/bleak), which has full macOS support). The
Spring Boot app manages that process as a subprocess and exposes it as a REST
+ Server-Sent-Events API for the frontend.

- `bridge/walkingpad_bridge.py` — the Python/bleak side; speaks newline-delimited
  JSON on stdin/stdout (see the docstring at the top of the file for the protocol).
- `src/main/java/com/walkingpad/WalkingPadClient.java` — Java-side process
  manager for the bridge.
- `src/main/java/com/walkingpad/service/WalkingPadService.java` — Spring bean
  wrapping the client, fans status updates out to SSE subscribers.
- `src/main/java/com/walkingpad/web/WalkingPadController.java` — REST API.
- `frontend/` — React + TypeScript UI (Vite).

## One-time setup

```bash
# Python bridge dependencies
cd bridge
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt
cd ..

# Frontend dependencies
cd frontend
npm install
cd ..
```

On first run, macOS will prompt for Bluetooth permission for your terminal
app (System Settings → Privacy & Security → Bluetooth) — grant it, otherwise
scanning silently finds nothing.

## Running

Two processes, in two terminals:

```bash
# Terminal 1 — backend (port 8090)
mvn spring-boot:run

# Terminal 2 — frontend (port 5173, proxies /api to the backend)
cd frontend && npm run dev
```

Open the URL Vite prints (typically http://localhost:5173).

In the UI: **Scan for devices** to find your pad's BLE address (or just hit
**Connect**, which auto-scans for a device whose name contains "WalkingPad"),
then **Connect**. Once connected, steps/distance/speed/time update live.
**Start belt** / **Stop belt** and speed control are also available.

## REST API

| Method | Path                          | Body                      | Notes |
|--------|-------------------------------|---------------------------|-------|
| GET    | `/api/walkingpad/scan?timeout=5` | —                       | List nearby BLE devices |
| POST   | `/api/walkingpad/connect`     | `{"address": "..."}` or `{}` | Omit/blank address to auto-scan |
| POST   | `/api/walkingpad/disconnect`  | —                          | |
| POST   | `/api/walkingpad/start`       | —                          | Starts the belt |
| POST   | `/api/walkingpad/stop`        | —                          | Stops the belt |
| POST   | `/api/walkingpad/speed`       | `{"value": 3.5}`          | km/h, ~0.5–6.0 |
| GET    | `/api/walkingpad/status`      | —                          | Latest cached status, 204 if none yet |
| GET    | `/api/walkingpad/status/stream` | —                        | Server-Sent Events stream of status updates |

## Configuration

`src/main/resources/application.properties`:

```
server.port=8090
walkingpad.python-executable=bridge/.venv/bin/python
walkingpad.bridge-script=bridge/walkingpad_bridge.py
```

Paths are resolved relative to the working directory the Spring Boot app is
started from (the project root, when using `mvn spring-boot:run`).

## Troubleshooting

- **Scan finds nothing**: Bluetooth permission not yet granted to the
  terminal — check System Settings → Privacy & Security → Bluetooth.
- **Connect times out**: the pad's BLE radio may be asleep (step on it or
  press its button to wake it) or the official WalkingPad phone app is still
  connected — only one BLE client can be connected at a time, so close the
  app first. macOS also generates a session-specific BLE address for each
  peripheral, so a stale address from an old scan can fail to connect — use
  a fresh scan, or just leave the address blank to auto-scan-and-connect.
- **Distance/speed units**: distance is derived from the raw protocol value
  divided by 100 (km), speed by 10 (km/h), per `ph4-walkingpad`'s own status
  parsing — these haven't been independently re-verified against a reference
  measurement, so treat them as approximate until cross-checked against the
  official app.
