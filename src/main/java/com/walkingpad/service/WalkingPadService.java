package com.walkingpad.service;

import com.walkingpad.BleDevice;
import com.walkingpad.WalkingPadClient;
import com.walkingpad.WalkingPadStatus;
import com.walkingpad.config.WalkingPadProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring-managed facade over {@link WalkingPadClient}: owns the single bridge
 * process for the app's lifetime and fans live status updates out to any
 * number of SSE subscribers (e.g. browser tabs).
 */
@Service
public class WalkingPadService {

    private final WalkingPadClient client;
    private final AtomicReference<WalkingPadStatus> latestStatus = new AtomicReference<>();
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public WalkingPadService(WalkingPadProperties props) {
        this.client = new WalkingPadClient(
                Path.of(props.getPythonExecutable()), Path.of(props.getBridgeScript()));
        this.client.setStatusListener(this::onStatus);
    }

    private void onStatus(WalkingPadStatus status) {
        latestStatus.set(status);
        emitters.forEach((id, emitter) -> {
            try {
                emitter.send(status);
            } catch (Exception e) {
                emitters.remove(id);
            }
        });
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        String id = UUID.randomUUID().toString();
        emitters.put(id, emitter);
        emitter.onCompletion(() -> emitters.remove(id));
        emitter.onTimeout(() -> emitters.remove(id));
        emitter.onError(e -> emitters.remove(id));

        WalkingPadStatus current = latestStatus.get();
        if (current != null) {
            try {
                emitter.send(current);
            } catch (Exception ignored) {
                emitters.remove(id);
            }
        }
        return emitter;
    }

    public List<BleDevice> scan(double timeoutSeconds) {
        return client.scan(timeoutSeconds);
    }

    public void connect(String address) {
        if (address == null || address.isBlank()) {
            client.connectAuto(5.0);
        } else {
            client.connect(address);
        }
    }

    public void disconnect() {
        client.disconnect();
    }

    public void start() {
        client.start();
    }

    public void stop() {
        client.stop();
    }

    public void setSpeed(double kmh) {
        client.setSpeedKmh(kmh);
    }

    public WalkingPadStatus getLatestStatus() {
        return latestStatus.get();
    }

    @PreDestroy
    public void shutdown() {
        client.close();
    }
}
