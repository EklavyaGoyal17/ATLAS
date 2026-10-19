package dev.atlas.payments;

import static dev.atlas.payments.PaymentModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @GetMapping("/payments")
    Page history(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return service.history(page, size);
    }

    @GetMapping("/payments/{id}")
    Detail detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping("/payments")
    ResponseEntity<Result> authorize(
        @RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody Authorization request
    ) {
        return response(service.authorize(key, request), 201);
    }

    @PostMapping("/payments/{id}/capture")
    ResponseEntity<Result> capture(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String key
    ) {
        return response(service.capture(id, key), 200);
    }

    @PostMapping("/payments/{id}/refund")
    ResponseEntity<Result> refund(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody Refund request
    ) {
        return response(service.refund(id, key, request), 200);
    }

    @PostMapping("/payments/{id}/reverse")
    ResponseEntity<Result> reverse(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String key
    ) {
        return response(service.reverse(id, key), 200);
    }

    @GetMapping("/ledger/reconciliation")
    List<Reconciliation> reconcile() {
        return service.reconcile();
    }

    private ResponseEntity<Result> response(Result result, int status) {
        return ResponseEntity.status(result.replayed() ? 200 : status)
            .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
            .body(result);
    }
}
