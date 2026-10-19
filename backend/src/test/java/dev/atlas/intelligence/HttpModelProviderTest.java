package dev.atlas.intelligence;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
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
}
