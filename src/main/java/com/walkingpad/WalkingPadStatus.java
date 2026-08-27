package com.walkingpad;

/** A single status snapshot reported by the WalkingPad over BLE. */
public record WalkingPadStatus(
        double distanceKm,
        int steps,
        int timeSec,
        double speedKmh,
        int beltState,
        int manualMode
) {
}
