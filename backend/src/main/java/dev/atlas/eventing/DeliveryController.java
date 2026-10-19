package dev.atlas.eventing;

import static dev.atlas.eventing.DeliveryModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DeliveryController {

    private final DeliveryService delivery;
    private final ExperimentService experiments;

    public DeliveryController(
        DeliveryService delivery,
        ExperimentService experiments
    ) {
        this.delivery = delivery;
        this.experiments = experiments;
    }

    @GetMapping("/events")
    Page events(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return delivery.events(page, size);
    }

    @GetMapping("/events/{id}")
    Detail detail(@PathVariable UUID id) {
        return delivery.detail(id);
    }

    @GetMapping("/worker")
    Control control() {
        return delivery.control();
    }

    @PutMapping("/worker")
    Control run(@Valid @RequestBody Run request) {
        return delivery.running(request.running());
    }

    @PostMapping("/worker/step")
    StepResult step(@Valid @RequestBody Step request) {
        return delivery.step(request, false);
    }

    @PostMapping("/events/{id}/redeliver")
    Detail redeliver(@PathVariable UUID id) {
        return delivery.redeliver(id);
    }

    @PostMapping("/experiments")
    ResponseEntity<Experiment> experiment(
        @RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody ExperimentRequest request
    ) {
        Experiment result = experiments.create(key, request);
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(
            result
        );
    }
}
