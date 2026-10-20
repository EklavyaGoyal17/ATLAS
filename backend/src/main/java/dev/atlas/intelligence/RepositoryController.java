package dev.atlas.intelligence;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repository")
public class RepositoryController {

    public record Import(
        @NotBlank @Size(max = 200) String path,
        boolean approved
    ) {}

    public record GitHubImport(
        @NotBlank @Size(max = 300) String url,
        @Size(max = 200) String directory,
        boolean approved
    ) {}

    public record ImpactRequest(
        @NotNull UUID snapshotId,
        @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 300) String> paths
    ) {}

    private final RepositoryIndex index;
    private final RepositoryMetadata metadata;
    private final RepositoryArchitecture architecture;
    private final RepositoryImpact impact;
    private final GitHubRepositoryImport github;

    public RepositoryController(
        RepositoryIndex index,
        RepositoryMetadata metadata,
        RepositoryArchitecture architecture,
        RepositoryImpact impact,
        GitHubRepositoryImport github
    ) {
        this.index = index;
        this.metadata = metadata;
        this.architecture = architecture;
        this.impact = impact;
        this.github = github;
    }

    @GetMapping("/metadata")
    public RepositoryMetadata.Report metadata(
        @RequestParam(required = false) UUID snapshotId
    ) {
        return metadata.report(snapshotId);
    }

    @GetMapping("/architecture")
    public RepositoryArchitecture.Report architecture() {
        return architecture.report();
    }

    @PostMapping("/impact")
    public RepositoryImpact.Report impact(
        @Valid @RequestBody ImpactRequest request
    ) {
        return impact.report(request.snapshotId(), request.paths());
    }

    @GetMapping("/configuration")
    public Map<String, Object> configuration() {
        return index.configuration();
    }

    @PostMapping("/import")
    public RepositoryIndex.Snapshot read(@Valid @RequestBody Import request) {
        return index.index(request.path(), request.approved());
    }

    @PostMapping("/github")
    public RepositoryIndex.Snapshot github(
        @Valid @RequestBody GitHubImport request
    ) {
        return github.importRepository(
            request.url(),
            request.directory(),
            request.approved()
        );
    }

    @GetMapping
    public RepositoryIndex.Snapshot snapshot() {
        return index.current();
    }

    @GetMapping("/file")
    public RepositoryIndex.Source file(@RequestParam String path) {
        return index.file(path);
    }

    @GetMapping("/git")
    public Map<String, String> git(@RequestParam String path) {
        return index.git(path);
    }

    @DeleteMapping
    public Map<String, Boolean> clear() {
        index.clear();
        return Map.of("deleted", true);
    }
}
