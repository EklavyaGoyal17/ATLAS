package dev.atlas.observability;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    public record Inject(@NotNull IncidentService.Scenario scenario) {}

    private final Telemetry telemetry;
    private final IncidentService incidents;
    private final IncidentArchive archive;

    public ObservabilityController(
        Telemetry telemetry,
        IncidentService incidents,
        IncidentArchive archive
    ) {
        this.telemetry = telemetry;
        this.incidents = incidents;
        this.archive = archive;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return telemetry.summary();
    }

    @GetMapping("/traces")
    public List<Telemetry.Trace> traces() {
        return telemetry.traces();
    }

    @PostMapping("/incidents")
    public IncidentService.Investigation run(
        @Valid @RequestBody Inject request
    ) {
        return incidents.run(request.scenario());
    }

    @GetMapping("/incidents/{id}/evidence")
    public IncidentArchive.Evidence evidence(@PathVariable UUID id) {
        return archive.get(id);
    }
}
