package dev.atlas.eventing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/simulations")
public class SimulationController {

    public record Run(@NotNull SimulationEngine.Scenario scenario, long seed) {}

    private final SimulationEngine engine;

    public SimulationController(SimulationEngine engine) {
        this.engine = engine;
    }

    @PostMapping
    public SimulationEngine.Result run(@Valid @RequestBody Run request) {
        return engine.run(request.scenario(), request.seed());
    }
}
