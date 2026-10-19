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

    private final RepositoryIndex index;
    private final RepositoryMetadata metadata;

    public RepositoryController(
        RepositoryIndex index,
        RepositoryMetadata metadata
    ) {
        this.index = index;
        this.metadata = metadata;
    }

    @GetMapping("/metadata")
    public RepositoryMetadata.Report metadata() {
        return metadata.report();
    }

    @GetMapping("/configuration")
    public Map<String, Object> configuration() {
        return index.configuration();
    }

    @PostMapping("/import")
    public RepositoryIndex.Snapshot read(@Valid @RequestBody Import request) {
        return index.index(request.path(), request.approved());
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
