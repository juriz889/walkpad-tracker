package com.walkingpad;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    @Test
    void subscribeFansOutLiveStatusUpdatesToAllSubscribers() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        // Tomcat doesn't flush the SseEmitter response's headers until the first event is
        // written, so a *synchronous* client.send() here would block forever waiting for
        // headers that only arrive once /connect (below) makes the bridge double start
        // pushing status. sendAsync lets both subscriptions sit open while /connect runs.
        CompletableFuture<HttpResponse<InputStream>> streamA = openSseStream(client);
        CompletableFuture<HttpResponse<InputStream>> streamB = openSseStream(client);

        assertThat(post("/api/walkingpad/connect").getStatusCode()).isEqualTo(HttpStatus.OK);

        // Let the double's status pump (one event per 100ms) tick a few times before we
        // start reading, so both events we compare below are already sitting in the socket
        // buffer instead of depending on live delivery timing during the read itself.
        Thread.sleep(400);

        InputStream bodyA = streamA.get(5, TimeUnit.SECONDS).body();
        InputStream bodyB = streamB.get(5, TimeUnit.SECONDS).body();
        // Daemon threads: closing the stream on a timeout doesn't reliably unblock a
        // thread parked in a blocking socket read, and a stuck non-daemon thread would
        // otherwise keep the JVM (and this test) alive past the assertion failure.
        ExecutorService readers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "sse-reader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<List<WalkingPadStatus>> eventsA = readers.submit(() -> readStatusEvents(bodyA, 2));
            Future<List<WalkingPadStatus>> eventsB = readers.submit(() -> readStatusEvents(bodyB, 2));

            List<WalkingPadStatus> receivedA = awaitEvents(eventsA, bodyA);
            List<WalkingPadStatus> receivedB = awaitEvents(eventsB, bodyB);

            assertThat(receivedA).hasSize(2);
            assertThat(receivedB).hasSize(2);
            assertThat(receivedA.get(1).steps()).isGreaterThan(receivedA.get(0).steps());
            assertThat(receivedB.get(1).steps()).isGreaterThan(receivedB.get(0).steps());
        } finally {
            readers.shutdownNow();
            bodyA.close();
            bodyB.close();
        }
    }

    private CompletableFuture<HttpResponse<InputStream>> openSseStream(HttpClient client) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/api/walkingpad/status/stream")))
                .GET()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .whenComplete((response, error) -> {
                    if (response != null) {
                        assertThat(response.statusCode()).isEqualTo(200);
                    }
                });
    }

    /** Reads {@code count} "data:" lines off an SSE stream, deserializing each as a status. */
    private static List<WalkingPadStatus> readStatusEvents(InputStream body, int count) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<WalkingPadStatus> events = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String line;
        while (events.size() < count && (line = reader.readLine()) != null) {
            if (line.startsWith("data:")) {
                events.add(mapper.readValue(line.substring("data:".length()).trim(), WalkingPadStatus.class));
            }
        }
        return events;
    }

    /** Closes the stream to unblock the reader's blocking read if it times out. */
    private static List<WalkingPadStatus> awaitEvents(Future<List<WalkingPadStatus>> future, InputStream body)
            throws Exception {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            body.close();
            throw new AssertionError("SSE subscriber did not receive 2 status events within 5s", e);
        }
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
