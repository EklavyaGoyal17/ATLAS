package dev.atlas.workspace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.atlas.platform.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AdrRulesTest {

    final JsonMapper json = JsonMapper.builder().build();

    JsonNode valid(String date) {
        var fields = new LinkedHashMap<String, Object>();
        AdrRules.FIELDS.forEach(field -> fields.put(field, "Explain " + field));
        fields.put("Review date", date);
        return json.valueToTree(fields);
    }

    @Test
    void completeCalendarDatesAndAdditionalLegacyFieldsAreAcceptedWithoutMutation() {
        for (String date : List.of("2028-02-29", "0001-01-01", "9999-12-31")) {
            var original = valid(date);
            var body = json.readTree(original.toString());
            AdrRules.validate(body);
            assertThat(body).isEqualTo(original);
        }
        var body = (tools.jackson.databind.node.ObjectNode) valid("2026-10-22");
        body.put("Legacy annotation", "Retain this public note");
        AdrRules.validate(body);
        assertThat(body.get("Legacy annotation").asString()).isEqualTo(
            "Retain this public note"
        );
    }

    @Test
    void everyRequiredFieldRejectsMissingBlankWrongTypeAndOverlongValues() {
        for (String field : AdrRules.FIELDS) {
            for (String replacement : List.of(
                "null",
                json.writeValueAsString(" \n "),
                "12",
                "true",
                "{}",
                "[]",
                json.writeValueAsString("x".repeat(1201))
            )) {
                var body = (tools.jackson.databind.node.ObjectNode) valid(
                    "2026-10-22"
                );
                body.set(field, json.readTree(replacement));
                rejects(body);
            }
            var body = (tools.jackson.databind.node.ObjectNode) valid(
                "2026-10-22"
            );
            body.remove(field);
            rejects(body);
        }
    }

    @Test
    void dateMustBeStrictAndActuallyExist() {
        for (String date : List.of(
            "0000-01-01",
            "2026-02-29",
            "2026-04-31",
            "2026-13-01",
            "2026-00-10",
            "2026-01-00",
            "2026-1-01",
            "2026-01-1",
            "2026-10-22T00:00:00Z",
            " 2026-10-22",
            "+2026-10-22",
            "10000-01-01"
        )) {
            rejects(valid(date));
        }
    }

    @Test
    void invalidWriteFailsBeforeAnyDatabaseMutation() {
        var jdbc = mock(JdbcTemplate.class);
        var documents = new Documents(jdbc, json);
        assertThatThrownBy(() ->
            documents.save(
                UUID.randomUUID(),
                new Documents.Write(
                    Documents.Kind.ADR,
                    "Incomplete",
                    json.readTree("{}"),
                    0
                )
            )
        ).isInstanceOfSatisfying(ApiException.class, error ->
            assertThat(error.code).isEqualTo("INVALID_ADR")
        );
        verifyNoInteractions(jdbc);
    }

    void rejects(JsonNode body) {
        assertThatThrownBy(() ->
            AdrRules.validate(body)
        ).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status.value()).isEqualTo(400);
            assertThat(error.code).isEqualTo("INVALID_ADR");
        });
    }
}
