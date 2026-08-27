package com.walkingpad;

/** A device found during a BLE scan. */
public record BleDevice(String name, String address) {
}
