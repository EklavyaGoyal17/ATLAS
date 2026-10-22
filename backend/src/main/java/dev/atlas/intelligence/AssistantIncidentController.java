package dev.atlas.intelligence;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant/incidents")
public class AssistantIncidentController {

    private final IncidentBriefs briefs;

    public AssistantIncidentController(IncidentBriefs briefs) {
        this.briefs = briefs;
    }

    @GetMapping("/{id}/summary")
    public IncidentBriefs.Summary summary(@PathVariable UUID id) {
        return briefs.get(id);
    }
}
