export interface WalkingPadStatus {
  distanceKm: number;
  steps: number;
  timeSec: number;
  speedKmh: number;
  beltState: number;
  manualMode: number;
}

export interface BleDevice {
  name: string;
  address: string;
}

export type ConnectionState = "disconnected" | "connecting" | "connected" | "error";
