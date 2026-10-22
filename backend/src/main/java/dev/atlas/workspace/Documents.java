package dev.atlas.workspace;

import dev.atlas.platform.ApiException;
import dev.atlas.platform.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class Documents {

    public enum Kind {
        DIAGRAM,
        ADR,
        JOURNAL,
        LEARNING,
        POSTMORTEM,
        REVIEW,
        INCIDENT,
    }

    public record Write(
        @NotNull Kind kind,
        @NotBlank @Size(max = 120) String title,
        @NotNull JsonNode body,
        @Min(0) int expectedVersion
    ) {}

    public record Document(
        UUID id,
        Kind kind,
        String title,
        int version,
        JsonNode body,
        Instant updatedAt
    ) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public Documents(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Document> list() {
        return list(0, null);
    }

    public List<Document> list(int page, Kind kind) {
        if (page < 0 || page > 4) throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "INVALID_PAGE",
            "Use workspace page 0–4, with at most 200 records per page."
        );
        var arguments = new ArrayList<Object>();
        if (kind != null) arguments.add(kind.name());
        arguments.add(page * 200);
        return jdbc.query(
            "SELECT * FROM workspace_document " +
                (kind == null ? "" : "WHERE kind=? ") +
                "ORDER BY updated_at DESC,id DESC LIMIT 200 OFFSET ?",
            (r, n) ->
                new Document(
                    r.getObject("id", UUID.class),
                    Kind.valueOf(r.getString("kind")),
                    r.getString("title"),
                    r.getInt("version"),
                    json.readTree(r.getString("body_json")),
                    r.getTimestamp("updated_at").toInstant()
                ),
            arguments.toArray()
        );
    }

    public List<Map<String, Object>> revisions(UUID id) {
        return jdbc.queryForList(
            "SELECT version,title,body_json,created_at FROM document_revision WHERE document_id=? ORDER BY version DESC LIMIT 20",
            id
        );
    }

    @Transactional
    public Document save(UUID id, Write request) {
        String body = json.writeValueAsString(request.body());
        if (
            !request.body().isObject() ||
            body.getBytes(StandardCharsets.UTF_8).length > 12000
        ) throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "DOCUMENT_LIMIT",
            "Document must be a JSON object under 12 KiB."
        );
        if (request.kind() == Kind.DIAGRAM) DiagramRules.validate(
            request.body()
        );
        if (request.kind() == Kind.ADR) AdrRules.validate(request.body());
        // One lock bounds document creation and makes optimistic version checks atomic.
        jdbc.execute("SELECT pg_advisory_xact_lock(818103)");
        int version = request.expectedVersion() + 1;
        if (request.expectedVersion() == 0) {
            if (
                jdbc.queryForObject(
                    "SELECT count(*) FROM workspace_document",
                    Long.class
                ) >= 1000
            ) throw new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "DOCUMENT_LIMIT",
                "Export and delete older documents; limit is 1000."
            );
            if (
                jdbc.queryForObject(
                    "SELECT count(*) FROM workspace_document WHERE id=?",
                    Long.class,
                    id
                ) > 0
            ) conflict();
            jdbc.update(
                "INSERT INTO workspace_document(id,kind,title,version,body_json) VALUES(?,?,?,?,?)",
                id,
                request.kind().name(),
                request.title(),
                version,
                body
            );
        } else if (
            jdbc.update(
                "UPDATE workspace_document SET title=?,body_json=?,version=?,updated_at=now() WHERE id=? AND version=? AND kind=?",
                request.title(),
                body,
                version,
                id,
                request.expectedVersion(),
                request.kind().name()
            ) != 1
        ) conflict();
        jdbc.update(
            "INSERT INTO document_revision(document_id,version,title,body_json) VALUES(?,?,?,?)",
            id,
            version,
            request.title(),
            body
        );
        jdbc.update(
            "DELETE FROM document_revision WHERE document_id=? AND version<=?",
            id,
            version - 20
        );
        audit("DOCUMENT_SAVED", id.toString());
        return new Document(
            id,
            request.kind(),
            request.title(),
            version,
            request.body(),
            Instant.now()
        );
    }

    private void conflict() {
        throw new ApiException(
            HttpStatus.CONFLICT,
            "VERSION_CONFLICT",
            "Document changed. Reload before saving."
        );
    }

    @Transactional
    public void delete(UUID id) {
        jdbc.update("DELETE FROM workspace_document WHERE id=?", id);
        audit("DOCUMENT_DELETED", id.toString());
    }

    public void audit(String action, String target) {
        jdbc.update(
            "INSERT INTO workspace_audit(action,target,correlation_id) VALUES(?,?,?)",
            action,
            target,
            RequestContext.correlationId()
        );
        jdbc.update(
            "DELETE FROM workspace_audit WHERE sequence < (SELECT COALESCE(max(sequence),0)-2000 FROM workspace_audit)"
        );
    }
}
