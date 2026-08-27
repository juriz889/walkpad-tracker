package com.walkingpad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Java-side handle to a KingSmith WalkingPad, backed by a small Python/bleak
 * bridge process (macOS has no native Java BLE stack; bleak's CoreBluetooth
 * support does the real work).
 *
 * <p>Commands are serialized (the bridge itself has a single asyncio event
 * loop and processes one command at a time), so all public methods here block
 * until the bridge acknowledges the command.
 */
public final class WalkingPadClient implements AutoCloseable {

    private static final long RESPONSE_TIMEOUT_SECONDS = 15;
    private static final long CONNECT_TIMEOUT_SECONDS = 30;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Process process;
    private final Writer stdin;
    private final BlockingQueue<JsonNode> responses = new LinkedBlockingQueue<>();
    private final ReentrantLock commandLock = new ReentrantLock();
    private final Thread readerThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Consumer<WalkingPadStatus> statusListener = status -> { };

    public WalkingPadClient(Path pythonExecutable, Path bridgeScript) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    pythonExecutable.toString(), bridgeScript.toString());
            builder.redirectErrorStream(false);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            this.process = builder.start();
        } catch (IOException e) {
            throw new WalkingPadException("Failed to start bridge process", e);
        }

        this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        this.readerThread = new Thread(this::readLoop, "walkingpad-bridge-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();

        awaitResponse("ready");
    }

    /** Registers a callback invoked on every status update pushed by the pad. */
    public void setStatusListener(Consumer<WalkingPadStatus> listener) {
        this.statusListener = listener == null ? status -> { } : listener;
    }

    /** Scans for nearby BLE devices for {@code timeoutSeconds}. */
    public List<BleDevice> scan(double timeoutSeconds) {
        ObjectNode cmd = mapper.createObjectNode();
        cmd.put("cmd", "scan");
        cmd.put("timeout", timeoutSeconds);
        JsonNode result = sendAndAwait(cmd, "scan_result");

        List<BleDevice> devices = new ArrayList<>();
        result.get("devices").forEach(d ->
                devices.add(new BleDevice(d.get("name").asText(), d.get("address").asText())));
        return devices;
    }

    /** Connects to the WalkingPad at the given BLE address. */
    public void connect(String address) {
        ObjectNode cmd = mapper.createObjectNode();
        cmd.put("cmd", "connect");
        cmd.put("address", address);
        sendAndAwait(cmd, "ack", CONNECT_TIMEOUT_SECONDS);
    }

    /** Connects to the first device whose advertised name looks like a WalkingPad. */
    public void connectAuto(double scanTimeoutSeconds) {
        ObjectNode cmd = mapper.createObjectNode();
        cmd.put("cmd", "connect");
        cmd.put("timeout", scanTimeoutSeconds);
        sendAndAwait(cmd, "ack", CONNECT_TIMEOUT_SECONDS);
    }

    public void disconnect() {
        ObjectNode cmd = mapper.createObjectNode();
        cmd.put("cmd", "disconnect");
        sendAndAwaitAck(cmd);
    }

    /** Starts the belt (manual mode). */
    public void start() {
        ObjectNode cmd = mapper.createObjectNode();
        cmd.put("cmd", "start");
        sendAndAwaitAck(cmd);
    }

    /** Stops the belt. */
    public void stop() {
        ObjectNode cmd = mapper.createObjectNode();
        cmd.put("cmd", "stop");
        sendAndAwaitAck(cmd);
    }

    /** Sets belt speed in km/h (device range is roughly 0.5 - 6.0 km/h). */
    public void setSpeedKmh(double kmh) {
        ObjectNode cmd = mapper.createObjectNode();
        cmd.put("cmd", "speed");
        cmd.put("value", kmh);
        sendAndAwaitAck(cmd);
    }

    /**
     * Requests a fresh status snapshot; the result arrives asynchronously via
     * the status listener, not as this call's return value. The bridge also
     * polls status once per second automatically while connected.
     */
    public void requestStatus() {
        ObjectNode cmd = mapper.createObjectNode();
        cmd.put("cmd", "status");
        sendAndAwaitAck(cmd);
    }

    private void sendAndAwaitAck(ObjectNode cmd) {
        sendAndAwait(cmd, "ack", RESPONSE_TIMEOUT_SECONDS);
    }

    private JsonNode sendAndAwait(ObjectNode cmd, String expectedType) {
        return sendAndAwait(cmd, expectedType, RESPONSE_TIMEOUT_SECONDS);
    }

    private JsonNode sendAndAwait(ObjectNode cmd, String expectedType, long timeoutSeconds) {
        commandLock.lock();
        try {
            responses.clear();
            send(cmd);
            JsonNode response = awaitResponse(expectedType, timeoutSeconds);
            if (!expectedType.equals(response.get("type").asText())) {
                throw new WalkingPadException("Unexpected response: " + response);
            }
            return response;
        } finally {
            commandLock.unlock();
        }
    }

    private void send(ObjectNode cmd) {
        try {
            stdin.write(mapper.writeValueAsString(cmd));
            stdin.write("\n");
            stdin.flush();
        } catch (IOException e) {
            throw new WalkingPadException("Failed to write to bridge process", e);
        }
    }

    private JsonNode awaitResponse(String expectedType) {
        return awaitResponse(expectedType, RESPONSE_TIMEOUT_SECONDS);
    }

    private JsonNode awaitResponse(String expectedType, long timeoutSeconds) {
        try {
            JsonNode response = responses.poll(timeoutSeconds, TimeUnit.SECONDS);
            if (response == null) {
                throw new WalkingPadException(
                        "Timed out waiting for '" + expectedType + "' from bridge process");
            }
            if ("error".equals(response.get("type").asText())) {
                throw new WalkingPadException("Bridge error: " + response.get("message").asText());
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WalkingPadException("Interrupted while waiting for bridge response", e);
        }
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode obj = mapper.readTree(line);
                String type = obj.has("type") ? obj.get("type").asText() : "";
                if ("status".equals(type)) {
                    statusListener.accept(toStatus(obj));
                } else {
                    responses.offer(obj);
                }
            }
        } catch (IOException e) {
            // Stream closed, typically because the bridge process exited (e.g. on close()).
        }
    }

    private static WalkingPadStatus toStatus(JsonNode obj) {
        return new WalkingPadStatus(
                obj.get("distanceKm").asDouble(),
                obj.get("steps").asInt(),
                obj.get("timeSec").asInt(),
                obj.get("speedKmh").asDouble(),
                obj.get("beltState").asInt(),
                obj.get("manualMode").asInt());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            commandLock.lock();
            try {
                ObjectNode cmd = mapper.createObjectNode();
                cmd.put("cmd", "quit");
                send(cmd);
            } finally {
                commandLock.unlock();
            }
            stdin.close();
            process.waitFor(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            process.destroyForcibly();
            readerThread.interrupt();
        }
    }
}
