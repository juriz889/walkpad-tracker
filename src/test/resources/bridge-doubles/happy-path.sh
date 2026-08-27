#!/bin/sh
# Test double for bridge/walkingpad_bridge.py: acknowledges every command and,
# once connected, emits one status event per second until disconnected/quit.
# Speaks the same newline-delimited JSON protocol as the real bridge so it can
# stand in for it behind WalkingPadClient in integration tests.

emit() { printf '%s\n' "$1"; }

status_pid=""

start_status_pump() {
  ( steps=0
    while :; do
      steps=$((steps + 1))
      emit "{\"type\":\"status\",\"distanceKm\":0.0,\"steps\":$steps,\"timeSec\":$steps,\"speedKmh\":3.0,\"beltState\":1,\"manualMode\":0}"
      sleep 1
    done ) &
  status_pid=$!
}

stop_status_pump() {
  [ -n "$status_pid" ] && kill "$status_pid" 2>/dev/null
  status_pid=""
}

trap stop_status_pump EXIT

emit '{"type":"ready"}'

while IFS= read -r line; do
  cmd=$(printf '%s' "$line" | sed -n 's/.*"cmd" *: *"\([a-zA-Z_]*\)".*/\1/p')
  case "$cmd" in
    quit)
      break
      ;;
    scan)
      emit '{"type":"scan_result","devices":[{"name":"WalkingPad A1","address":"AA:BB:CC:DD:EE:01"}]}'
      ;;
    connect)
      start_status_pump
      emit '{"type":"ack","cmd":"connect","ok":true}'
      ;;
    disconnect)
      stop_status_pump
      emit '{"type":"ack","cmd":"disconnect","ok":true}'
      ;;
    start|stop|speed|status)
      emit "{\"type\":\"ack\",\"cmd\":\"$cmd\",\"ok\":true}"
      ;;
    *)
      emit "{\"type\":\"error\",\"cmd\":\"$cmd\",\"message\":\"unknown command: $cmd\"}"
      ;;
  esac
done
