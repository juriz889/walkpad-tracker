package com.walkingpad;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the full Spring stack (controller -> service -> WalkingPadClient)
 * against the happy-path.sh bridge double, which stands in for
 * bridge/walkingpad_bridge.py so the test needs neither Python/bleak nor
 * real BLE hardware.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "walkingpad.python-executable=/bin/sh",
        "walkingpad.bridge-script=src/test/resources/bridge-doubles/happy-path.sh"
})
class HappyPathBridgeIntegrationTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<Void> post(String path) {
        RequestEntity<Void> request = RequestEntity.post(URI.create(url(path))).contentType(MediaType.APPLICATION_JSON)
                .build();
        return rest.exchange(request, Void.class);
    }

    @Test
    void scanReturnsDevicesReportedByTheBridge() {
        ResponseEntity<BleDevice[]> response = rest.getForEntity(url("/api/walkingpad/scan?timeout=1"),
                BleDevice[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(
                new BleDevice("WalkingPad A1", "AA:BB:CC:DD:EE:01"));
    }

    @Test
    void connectStartStopDisconnectAllSucceedAndStatusUpdatesArrive() {
        assertThat(post("/api/walkingpad/connect").getStatusCode()).isEqualTo(HttpStatus.OK);

        WalkingPadStatus status = awaitStatus();
        assertThat(status.beltState()).isEqualTo(1);

        assertThat(post("/api/walkingpad/start").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity(url("/api/walkingpad/speed"), Map.of("value", 3.5), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/walkingpad/stop").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/walkingpad/disconnect").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Polls GET /status until the bridge double's status pump delivers the first
     * reading.
     */
    private WalkingPadStatus awaitStatus() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<WalkingPadStatus> response = rest.getForEntity(url("/api/walkingpad/status"),
                    WalkingPadStatus.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                return response.getBody();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for status", e);
            }
        }
        throw new AssertionError("No status received from bridge double within 5s");
    }
}
