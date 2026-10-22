package dev.atlas.observability;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HttpOperationsTest {

    @Test
    void paymentOperationsHaveDistinctFixedTemplatesWithoutPathIdentifiers() {
        assertThat(HttpOperations.label("POST", "/api/payments")).isEqualTo(
            "HTTP POST /api/payments"
        );
        assertThat(HttpOperations.label("GET", "/api/payments")).isEqualTo(
            "HTTP GET /api/payments"
        );
        assertThat(
            HttpOperations.label("GET", "/api/payments/private-id")
        ).isEqualTo("HTTP GET /api/payments/{id}");
        for (String action : new String[] { "capture", "refund", "reverse" }) {
            assertThat(
                HttpOperations.label(
                    "POST",
                    "/api/payments/private-id/" + action
                )
            )
                .isEqualTo("HTTP POST /api/payments/{id}/" + action)
                .doesNotContain("private-id");
        }
    }

    @Test
    void arbitraryPathsAndMethodsStayInsideTheFixedVocabulary() {
        assertThat(HttpOperations.LABELS).hasSize(92);
        for (int i = 0; i < 2000; i++) {
            for (String method : new String[] {
                "GET",
                "POST",
                "secret-method-" + i,
            }) {
                for (String path : new String[] {
                    "/api/payments/private-" + i + "/capture",
                    "/api/payments/private-" + i + "/capture/more",
                    "/api/paymentsprefix-secret-" + i,
                    "/api/unknown-secret-" + i,
                    "/api/repository/secret-" + i,
                    "/api/payments//refund",
                }) {
                    assertThat(HttpOperations.label(method, path))
                        .isIn(HttpOperations.LABELS)
                        .doesNotContain("secret", "private", "more");
                }
            }
        }
        assertThat(
            HttpOperations.label("POST", "/api/paymentsprefix-secret")
        ).isEqualTo("HTTP POST /api/other");
        assertThat(HttpOperations.label(null, null)).isEqualTo(
            HttpOperations.UNKNOWN
        );
        assertThat(
            HttpOperations.knownLabel("HTTP GET /api/private?token=secret")
        ).isEqualTo(HttpOperations.UNKNOWN);
    }
}
