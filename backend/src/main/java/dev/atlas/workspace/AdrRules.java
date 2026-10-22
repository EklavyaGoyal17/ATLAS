package dev.atlas.workspace;

import dev.atlas.platform.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/** New writes must be complete; existing stored decisions remain readable. */
final class AdrRules {

    static final List<String> FIELDS = List.of(
        "Context",
        "Decision",
        "Alternatives",
        "Tradeoffs",
        "Consequences",
        "Review date"
    );

    private AdrRules() {}

    static void validate(JsonNode body) {
        for (String field : FIELDS) {
            JsonNode value = body.get(field);
            if (
                value == null ||
                !value.isString() ||
                value.asString().isBlank() ||
                value.asString().length() > 1200
            ) {
                reject(
                    "Each ADR field must contain 1–1200 characters: " +
                        String.join(", ", FIELDS) +
                        "."
                );
            }
        }
        String date = body.get("Review date").asString();
        try {
            if (
                !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") ||
                LocalDate.parse(date).getYear() < 1
            ) {
                rejectDate();
            }
        } catch (DateTimeParseException invalid) {
            rejectDate();
        }
    }

    private static void rejectDate() {
        reject(
            "ADR Review date must be a real calendar date in yyyy-MM-dd format, years 0001–9999."
        );
    }

    private static void reject(String message) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADR", message);
    }
}
