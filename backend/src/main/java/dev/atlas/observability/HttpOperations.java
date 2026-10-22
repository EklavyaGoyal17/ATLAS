package dev.atlas.observability;

import java.util.*;

/** Fixed telemetry vocabulary: request paths and methods can never become labels. */
final class HttpOperations {

    private static final Set<String> METHODS = Set.of(
        "GET",
        "POST",
        "PUT",
        "DELETE",
        "PATCH",
        "OPTIONS",
        "HEAD",
        "OTHER"
    );
    private static final List<String> FAMILIES = List.of(
        "payments",
        "events",
        "worker",
        "experiments",
        "ledger",
        "workspace",
        "repository",
        "assistant",
        "simulations",
        "webhooks",
        "other"
    );
    private static final Set<String> PAYMENT_ACTIONS = Set.of(
        "capture",
        "refund",
        "reverse"
    );
    static final String UNKNOWN = "HTTP OTHER /api/other";
    static final Set<String> LABELS;

    static {
        var labels = new HashSet<String>();
        for (String method : METHODS)
            for (String family : FAMILIES) {
                labels.add("HTTP " + method + " /api/" + family);
            }
        labels.add("HTTP GET /api/payments/{id}");
        for (String action : PAYMENT_ACTIONS) {
            labels.add("HTTP POST /api/payments/{id}/" + action);
        }
        LABELS = Set.copyOf(labels);
    }

    private HttpOperations() {}

    static String label(String rawMethod, String path) {
        String method =
            rawMethod != null && METHODS.contains(rawMethod)
                ? rawMethod
                : "OTHER";
        String family = FAMILIES.stream()
            .filter(
                f ->
                    path != null &&
                    (path.equals("/api/" + f) ||
                        path.startsWith("/api/" + f + "/"))
            )
            .findFirst()
            .orElse("other");
        if (family.equals("payments") && path.startsWith("/api/payments/")) {
            String rest = path.substring("/api/payments/".length());
            int separator = rest.indexOf('/');
            if (method.equals("GET") && !rest.isEmpty() && separator < 0) {
                return "HTTP GET /api/payments/{id}";
            }
            if (method.equals("POST") && separator > 0) {
                String action = rest.substring(separator + 1);
                if (PAYMENT_ACTIONS.contains(action)) {
                    return "HTTP POST /api/payments/{id}/" + action;
                }
            }
        }
        return "HTTP " + method + " /api/" + family;
    }

    static String knownLabel(String spanName) {
        return LABELS.contains(spanName) ? spanName : UNKNOWN;
    }
}
