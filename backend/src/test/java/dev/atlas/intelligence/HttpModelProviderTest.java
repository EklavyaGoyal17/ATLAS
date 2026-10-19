package dev.atlas.intelligence;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class HttpModelProviderTest {

    @Test
    void bridgeRoundTripHasBoundedRedactedPayloadAndValidTypedResult()
        throws Exception {
        var received = new AtomicReference<String>();
        var server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0),
            0
        );
        server.createContext("/explain", exchange -> {
            received.set(
                new String(
                    exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8
                )
            );
            byte[] response =
                "{\"answer\":\"Inspect the transaction\",\"citationIds\":[\"S1\"]}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            var provider = new HttpModelProvider(
                "http://127.0.0.1:" +
                    server.getAddress().getPort() +
                    "/explain",
                "",
                new JsonMapper()
            );
            var result = provider.explain(
                "api_key = secretvalue",
                List.of(
                    new ModelProvider.Context(
                        "S1",
                        "Example.java",
                        1,
                        1,
                        "@Transactional"
                    )
                )
            );
            assertThat(result.citationIds()).containsExactly("S1");
            assertThat(received.get())
                .doesNotContain("secretvalue")
                .contains("untrusted source data");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnsafeProviderConfiguration() {
        assertThatThrownBy(() ->
            new HttpModelProvider(
                "http://example.com/explain",
                "",
                new JsonMapper()
            )
        ).hasMessageContaining("HTTPS");
        assertThatThrownBy(() ->
            new HttpModelProvider(
                "https://user:password@example.com",
                "",
                new JsonMapper()
            )
        ).hasMessageContaining("credentials");
        assertThat(
            new HttpModelProvider("", "", new JsonMapper()).configured()
        ).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = { 503, 302, 200 })
    void realHttpFailuresRedirectsAndOversizedBodiesAreRejected(int status)
        throws Exception {
        var followed = new AtomicInteger();
        var server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0),
            0
        );
        server.createContext("/explain", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (status == 302) exchange
                .getResponseHeaders()
                .set("Location", "/must-not-follow");
            byte[] response = (
                status == 200
                    ? "x".repeat(16001)
                    : "{\"error\":\"Synthetic provider failure\"}"
            ).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.createContext("/must-not-follow", exchange -> {
            followed.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            var provider = new HttpModelProvider(
                "http://127.0.0.1:" +
                    server.getAddress().getPort() +
                    "/explain",
                "synthetic-test-key",
                new JsonMapper()
            );
            assertThatThrownBy(() ->
                provider.explain(
                    "Synthetic source question",
                    List.of(
                        new ModelProvider.Context(
                            "S1",
                            "Example.java",
                            1,
                            1,
                            "class Example {}"
                        )
                    )
                )
            )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable or oversized");
            assertThat(followed.get())
                .as("Credentials and source must not follow a redirect")
                .isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(12)
    void receivedHeadersDoNotDisableTheBodyReadDeadline() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0),
            0
        );
        server.createContext("/explain", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(
                    "{\"answer\":\"".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8
                    )
                );
                out.flush();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        try {
            var provider = new HttpModelProvider(
                "http://127.0.0.1:" +
                    server.getAddress().getPort() +
                    "/explain",
                "",
                new JsonMapper()
            );
            assertThatThrownBy(() ->
                provider.explain(
                    "Synthetic source question",
                    List.of(
                        new ModelProvider.Context(
                            "S1",
                            "Example.java",
                            1,
                            1,
                            "class Example {}"
                        )
                    )
                )
            )
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("body deadline exceeded");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
