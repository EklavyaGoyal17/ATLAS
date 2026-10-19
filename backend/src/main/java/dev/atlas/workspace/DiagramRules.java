package dev.atlas.workspace;

import dev.atlas.platform.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class DiagramRules {

    static void validate(JsonNode body) {
        var nodes = body.get("nodes");
        var edges = body.get("edges");
        if (
            nodes == null ||
            !nodes.isArray() ||
            nodes.size() > 24 ||
            edges == null ||
            !edges.isArray() ||
            edges.size() > 48 ||
            !text(body, "assumptions", 2000)
        ) reject();
        var ids = new HashSet<String>();
        for (var node : nodes) {
            if (
                !text(node, "id", 80) ||
                !text(node, "name", 50) ||
                !text(node, "type", 40) ||
                !text(node, "responsibility", 500) ||
                !ids.add(node.get("id").asText())
            ) reject();
            for (String coordinate : List.of("x", "y", "replicas"))
                if (
                    !node.has(coordinate) ||
                    !node.get(coordinate).isNumber() ||
                    !Double.isFinite(node.get(coordinate).asDouble())
                ) reject();
            if (
                node.get("x").asDouble() < 0 ||
                node.get("x").asDouble() > 1000 ||
                node.get("y").asDouble() < 0 ||
                node.get("y").asDouble() > 640 ||
                !node.get("replicas").isIntegralNumber() ||
                node.get("replicas").asInt() < 1 ||
                node.get("replicas").asInt() > 100
            ) reject();
        }
        var edgeIds = new HashSet<String>();
        for (var edge : edges)
            if (
                !text(edge, "id", 80) ||
                !edgeIds.add(edge.get("id").asText()) ||
                !text(edge, "pattern", 80) ||
                !text(edge, "from", 80) ||
                !text(edge, "to", 80) ||
                !ids.contains(edge.get("from").asText()) ||
                !ids.contains(edge.get("to").asText()) ||
                edge.get("from").asText().equals(edge.get("to").asText())
            ) reject();
    }

    private static boolean text(JsonNode node, String field, int limit) {
        return (
            node.has(field) &&
            node.get(field).isString() &&
            node.get(field).asText().length() <= limit
        );
    }

    private static void reject() {
        throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "INVALID_DIAGRAM",
            "Check bounded components, unique IDs, valid coordinates, instance counts and existing connection endpoints."
        );
    }
}
