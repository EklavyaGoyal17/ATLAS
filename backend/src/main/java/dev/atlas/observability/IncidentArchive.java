package dev.atlas.observability;

import dev.atlas.platform.ApiException;
import dev.atlas.workspace.Documents;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Bounded, immutable evidence stored atomically with its investigation document. */
@Service
public class IncidentArchive {

    public static final int MAX_SPANS = 128;
    public static final int MAX_BYTES = 128000;

    public record Evidence(
        UUID id,
        boolean available,
        IncidentService.Investigation investigation,
        List<Telemetry.Trace> spans,
        boolean truncated,
        String scope
    ) {}

    private final JdbcTemplate jdbc;
    private final Documents documents;
    private final JsonMapper json;

    public IncidentArchive(
        JdbcTemplate jdbc,
        Documents documents,
        JsonMapper json
    ) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.json = json;
    }

    @Transactional
    public void save(
        IncidentService.Investigation investigation,
        List<Telemetry.Trace> captured
    ) {
        var allowed = new HashSet<String>();
        investigation.baseline().forEach(s -> allowed.add(s.traceId()));
        investigation.affected().forEach(s -> allowed.add(s.traceId()));
        var relevant = captured
            .stream()
            .filter(s -> allowed.contains(s.traceId()))
            .distinct()
            .toList();
        var spans = new ArrayList<>(
            relevant.stream().limit(MAX_SPANS).toList()
        );
        boolean truncated = relevant.size() > spans.size();
        String encoded;
        while (true) {
            encoded = json.writeValueAsString(
                new Evidence(
                    investigation.id(),
                    true,
                    investigation,
                    List.copyOf(spans),
                    truncated,
                    "Saved sample spans captured at sample completion; at most 128 spans and 128000 UTF-8 bytes. Live telemetry may expire independently. No SQL, request bodies or repository content is retained. This is bounded evidence, not a complete tracing archive."
                )
            );
            if (
                encoded.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES
            ) break;
            if (spans.isEmpty()) throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INCIDENT_EVIDENCE_LIMIT",
                "Investigation exceeds the evidence budget."
            );
            spans.removeLast();
            truncated = true;
        }
        documents.save(
            investigation.id(),
            new Documents.Write(
                Documents.Kind.INCIDENT,
                "Investigation: " + investigation.scenario(),
                json.valueToTree(investigation),
                0
            )
        );
        jdbc.update(
            "INSERT INTO incident_evidence(incident_id,evidence_json) VALUES(?,?)",
            investigation.id(),
            encoded
        );
    }

    @Transactional(
        readOnly = true,
        isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ
    )
    public Evidence get(UUID id) {
        var evidence = jdbc.query(
            "SELECT evidence_json FROM incident_evidence WHERE incident_id=?",
            (r, n) -> json.readValue(r.getString(1), Evidence.class),
            id
        );
        if (!evidence.isEmpty()) return evidence.getFirst();
        if (
            jdbc.queryForObject(
                "SELECT count(*) FROM workspace_document WHERE id=? AND kind='INCIDENT'",
                Integer.class,
                id
            ) == 0
        ) throw new ApiException(
            HttpStatus.NOT_FOUND,
            "INCIDENT_NOT_FOUND",
            "Investigation not found."
        );
        return new Evidence(
            id,
            false,
            null,
            List.of(),
            false,
            "This investigation predates saved span evidence. Its sample measurements remain available, but expired child spans cannot be reconstructed."
        );
    }
}
