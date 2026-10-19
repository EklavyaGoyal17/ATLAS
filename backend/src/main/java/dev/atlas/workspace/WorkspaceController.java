package dev.atlas.workspace;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private final Documents documents;
    private final JdbcTemplate jdbc;

    public WorkspaceController(Documents documents, JdbcTemplate jdbc) {
        this.documents = documents;
        this.jdbc = jdbc;
    }

    @GetMapping("/documents")
    public List<Documents.Document> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(required = false) Documents.Kind kind
    ) {
        return documents.list(page, kind);
    }

    @PutMapping("/documents/{id}")
    public Documents.Document save(
        @PathVariable UUID id,
        @Valid @RequestBody Documents.Write request
    ) {
        if (
            request.kind() == Documents.Kind.INCIDENT
        ) throw new dev.atlas.platform.ApiException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "SYSTEM_MANAGED_DOCUMENT",
            "Investigations are created by the incident runner; use a postmortem for your own analysis."
        );
        return documents.save(id, request);
    }

    @GetMapping("/documents/{id}/revisions")
    public List<Map<String, Object>> versions(@PathVariable UUID id) {
        return documents.revisions(id);
    }

    @DeleteMapping("/documents/{id}")
    public Map<String, Boolean> delete(@PathVariable UUID id) {
        documents.delete(id);
        return Map.of("deleted", true);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit() {
        return jdbc.queryForList(
            "SELECT * FROM workspace_audit ORDER BY sequence DESC LIMIT 100"
        );
    }
}
