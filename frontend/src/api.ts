import type { BleDevice, WalkingPadStatus } from "./types";

async function request(path: string, init?: RequestInit): Promise<Response> {
  const res = await fetch(path, {
    headers: init?.body ? { "Content-Type": "application/json" } : undefined,
    ...init,
  });
  if (!res.ok) {
    const body = await res.text();
    let message = body;
    try {
      message = JSON.parse(body).error ?? body;
    } catch {
      // not JSON, use raw text
    }
    throw new Error(message || `Request failed: ${res.status}`);
  }
  return res;
}

export async function scan(timeoutSeconds = 5): Promise<BleDevice[]> {
  const res = await request(`/api/walkingpad/scan?timeout=${timeoutSeconds}`);
  return res.json();
}

export async function connect(address?: string): Promise<void> {
  await request("/api/walkingpad/connect", {
    method: "POST",
    body: JSON.stringify({ address: address ?? null }),
  });
}

export async function disconnect(): Promise<void> {
  await request("/api/walkingpad/disconnect", { method: "POST" });
}

export async function startBelt(): Promise<void> {
  await request("/api/walkingpad/start", { method: "POST" });
}

export async function stopBelt(): Promise<void> {
  await request("/api/walkingpad/stop", { method: "POST" });
}

export async function setSpeed(kmh: number): Promise<void> {
  await request("/api/walkingpad/speed", {
    method: "POST",
    body: JSON.stringify({ value: kmh }),
  });
}

export async function getStatus(): Promise<WalkingPadStatus | null> {
  const res = await fetch("/api/walkingpad/status");
  if (res.status === 204) {
    return null;
  }
  if (!res.ok) {
    throw new Error(`Request failed: ${res.status}`);
  }
  return res.json();
}

export function subscribeStatus(
  onStatus: (status: WalkingPadStatus) => void,
  onError?: (event: Event) => void,
): () => void {
  const source = new EventSource("/api/walkingpad/status/stream");
  source.onmessage = (event) => {
    onStatus(JSON.parse(event.data));
  };
  if (onError) {
    source.onerror = onError;
  }
  return () => source.close();
}
