package com.walkingpad;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the full Spring stack against the command-error.sh bridge double,
 * which acks everything except "start", to verify that a
 * {@code {"type":"error"}} response from the bridge surfaces as a 502 via
 * WalkingPadController's WalkingPadException handler.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "walkingpad.python-executable=/bin/sh",
        "walkingpad.bridge-script=src/test/resources/bridge-doubles/command-error.sh"
})
class CommandErrorBridgeIntegrationTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void bridgeErrorResponseSurfacesAsBadGateway() {
        assertThat(post("/api/walkingpad/connect").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, String>> response = rest.exchange(
                RequestEntity.post(URI.create(url("/api/walkingpad/start")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"),
                new ParameterizedTypeReference<Map<String, String>>() { });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).containsEntry("error", "Bridge error: belt not responding");
    }

    /**
     * Posts an empty JSON object rather than a truly bodyless POST: the JDK
     * HttpURLConnection-based request factory used here silently attaches a
     * "Content-Type: application/x-www-form-urlencoded" header to any POST
     * with an output stream and no explicit content type (even a zero-length
     * one), which trips a 415 on the {@code @RequestBody(required = false)}
     * /connect endpoint. Setting the content type ourselves avoids that.
     */
    private ResponseEntity<Void> post(String path) {
        RequestEntity<String> request = RequestEntity.post(URI.create(url(path)))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}");
        return rest.exchange(request, Void.class);
    }
}
