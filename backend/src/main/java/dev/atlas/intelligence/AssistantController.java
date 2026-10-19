package dev.atlas.intelligence;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    public record Question(@NotBlank @Size(max = 1500) String question) {}

    public record Ask(
        @NotBlank @Size(max = 1500) String question,
        boolean useProvider,
        boolean approved,
        @Size(max = 5) List<String> approvedIds
    ) {}

    private final AssistantService assistant;

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @GetMapping("/configuration")
    public Map<String, Object> configuration() {
        return assistant.configuration();
    }

    @PostMapping("/preview")
    public List<ModelProvider.Context> preview(
        @Valid @RequestBody Question request
    ) {
        return assistant.retrieve(request.question());
    }

    @PostMapping
    public AssistantService.Answer ask(@Valid @RequestBody Ask request) {
        return assistant.ask(
            request.question(),
            request.useProvider(),
            request.approved(),
            request.approvedIds()
        );
    }
}
