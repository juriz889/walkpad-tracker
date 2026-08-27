import { useEffect, useRef, useState } from "react";
import "./App.css";
import {
  connect,
  disconnect,
  getStatus,
  scan,
  setSpeed,
  startBelt,
  stopBelt,
  subscribeStatus,
} from "./api";
import type { BleDevice, ConnectionState, WalkingPadStatus } from "./types";

function App() {
  const [devices, setDevices] = useState<BleDevice[]>([]);
  const [scanning, setScanning] = useState(false);
  const [selectedAddress, setSelectedAddress] = useState("");
  const [connectionState, setConnectionState] = useState<ConnectionState>("disconnected");
  const [status, setStatus] = useState<WalkingPadStatus | null>(null);
  const [speedInput, setSpeedInput] = useState(3.0);
  const [error, setError] = useState<string | null>(null);
  const unsubscribeRef = useRef<(() => void) | undefined>(undefined);

  useEffect(() => {
    getStatus()
      .then((s) => {
        if (s) {
          setStatus(s);
          setConnectionState("connected");
          ensureSubscribed();
        }
      })
      .catch(() => {});
    return () => unsubscribeRef.current?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function ensureSubscribed() {
    if (unsubscribeRef.current) return;
    unsubscribeRef.current = subscribeStatus(
      (s) => setStatus(s),
      () => setError("Lost live status connection"),
    );
  }

  async function handleScan() {
    setError(null);
    setScanning(true);
    try {
      const found = await scan(5);
      setDevices(found.filter((d) => d.address));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setScanning(false);
    }
  }

  async function handleConnect() {
    setError(null);
    setConnectionState("connecting");
    try {
      await connect(selectedAddress || undefined);
      setConnectionState("connected");
      ensureSubscribed();
    } catch (e) {
      setConnectionState("error");
      setError((e as Error).message);
    }
  }

  async function handleDisconnect() {
    setError(null);
    try {
      await disconnect();
      setConnectionState("disconnected");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleStart() {
    setError(null);
    try {
      await startBelt();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleStop() {
    setError(null);
    try {
      await stopBelt();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleSetSpeed() {
    setError(null);
    try {
      await setSpeed(speedInput);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div className="app">
      <header>
        <h1>WalkingPad</h1>
        <span className={`badge badge-${connectionState}`}>{connectionState}</span>
      </header>

      {error && <div className="error-banner">{error}</div>}

      <section className="card">
        <h2>Device</h2>
        <div className="row">
          <button onClick={handleScan} disabled={scanning}>
            {scanning ? "Scanning..." : "Scan for devices"}
          </button>
        </div>
        {devices.length > 0 && (
          <select value={selectedAddress} onChange={(e) => setSelectedAddress(e.target.value)}>
            <option value="">Auto-detect WalkingPad</option>
            {devices.map((d) => (
              <option key={d.address} value={d.address}>
                {d.name || "(unnamed)"} — {d.address}
              </option>
            ))}
          </select>
        )}
        <div className="row">
          <button
            onClick={handleConnect}
            disabled={connectionState === "connecting" || connectionState === "connected"}
          >
            Connect
          </button>
          <button onClick={handleDisconnect} disabled={connectionState !== "connected"}>
            Disconnect
          </button>
        </div>
      </section>

      <section className="card">
        <h2>Live status</h2>
        <div className="stats">
          <Stat label="Steps" value={status ? status.steps.toString() : "—"} />
          <Stat label="Distance" value={status ? `${status.distanceKm.toFixed(2)} km` : "—"} />
          <Stat label="Speed" value={status ? `${status.speedKmh.toFixed(1)} km/h` : "—"} />
          <Stat label="Time" value={status ? formatTime(status.timeSec) : "—"} />
        </div>
      </section>

      <section className="card">
        <h2>Controls</h2>
        <div className="row">
          <button onClick={handleStart} disabled={connectionState !== "connected"}>
            Start belt
          </button>
          <button onClick={handleStop} disabled={connectionState !== "connected"}>
            Stop belt
          </button>
        </div>
        <div className="row">
          <input
            type="number"
            min={0.5}
            max={6}
            step={0.1}
            value={speedInput}
            onChange={(e) => setSpeedInput(Number(e.target.value))}
          />
          <span>km/h</span>
          <button onClick={handleSetSpeed} disabled={connectionState !== "connected"}>
            Set speed
          </button>
        </div>
      </section>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="stat">
      <div className="stat-value">{value}</div>
      <div className="stat-label">{label}</div>
    </div>
  );
}

function formatTime(totalSeconds: number): string {
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  const pad = (n: number) => n.toString().padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

export default App;
