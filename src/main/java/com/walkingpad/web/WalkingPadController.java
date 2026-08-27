package com.walkingpad.web;

import com.walkingpad.BleDevice;
import com.walkingpad.WalkingPadException;
import com.walkingpad.WalkingPadStatus;
import com.walkingpad.service.WalkingPadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/walkingpad")
public class WalkingPadController {

    private final WalkingPadService service;

    public WalkingPadController(WalkingPadService service) {
        this.service = service;
    }

    @GetMapping("/scan")
    public List<BleDevice> scan(@RequestParam(name = "timeout", defaultValue = "5") double timeout) {
        return service.scan(timeout);
    }

    @PostMapping("/connect")
    public void connect(@RequestBody(required = false) ConnectRequest request) {
        service.connect(request == null ? null : request.address());
    }

    @PostMapping("/disconnect")
    public void disconnect() {
        service.disconnect();
    }

    @PostMapping("/start")
    public void start() {
        service.start();
    }

    @PostMapping("/stop")
    public void stop() {
        service.stop();
    }

    @PostMapping("/speed")
    public void setSpeed(@RequestBody SpeedRequest request) {
        service.setSpeed(request.value());
    }

    @GetMapping("/status")
    public ResponseEntity<WalkingPadStatus> status() {
        WalkingPadStatus status = service.getLatestStatus();
        return status == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(status);
    }

    @GetMapping("/status/stream")
    public SseEmitter statusStream() {
        return service.subscribe();
    }

    @ExceptionHandler(WalkingPadException.class)
    public ResponseEntity<Map<String, String>> handleWalkingPadException(WalkingPadException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    public record ConnectRequest(String address) {
    }

    public record SpeedRequest(double value) {
    }
}
