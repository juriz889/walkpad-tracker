#!/bin/sh
# Test double for bridge/walkingpad_bridge.py: behaves like happy-path.sh
# except "start" always fails, to exercise WalkingPadClient/WalkingPadService
# error handling (bridge {"type":"error"} responses -> WalkingPadException).

emit() { printf '%s\n' "$1"; }

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
      emit '{"type":"ack","cmd":"connect","ok":true}'
      ;;
    start)
      emit '{"type":"error","cmd":"start","message":"belt not responding"}'
      ;;
    stop|speed|status|disconnect)
      emit "{\"type\":\"ack\",\"cmd\":\"$cmd\",\"ok\":true}"
      ;;
    *)
      emit "{\"type\":\"error\",\"cmd\":\"$cmd\",\"message\":\"unknown command: $cmd\"}"
      ;;
  esac
done
