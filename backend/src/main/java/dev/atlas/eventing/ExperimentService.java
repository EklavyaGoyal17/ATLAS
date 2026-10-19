package dev.atlas.eventing;

import static dev.atlas.eventing.DeliveryModels.*;

import dev.atlas.payments.PaymentModels.Authorization;
import dev.atlas.payments.PaymentService;
import dev.atlas.platform.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExperimentService {

    private final DeliveryStore store;
    private final PaymentService payments;

    public ExperimentService(DeliveryStore store, PaymentService payments) {
        this.store = store;
        this.payments = payments;
    }

    @Transactional
    public Experiment create(String key, ExperimentRequest request) {
        if (
            key == null || !key.matches("[A-Za-z0-9._:-]{8,80}")
        ) throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "INVALID_IDEMPOTENCY_KEY",
            "Experiment keys use 8–80 ASCII key characters."
        );
        boolean running = store.lockControl(); // Serializes setup against scheduler claims and other experiment requests.
        var cached = store.experiment(key);
        if (cached.isPresent()) {
            if (
                cached.get().scenario() != request.scenario()
            ) throw new ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "Experiment key belongs to a different scenario."
            );
            return cached.get();
        }
        if (running) throw new ApiException(
            HttpStatus.CONFLICT,
            "WORKER_RUNNING",
            "Pause the worker before preparing an experiment."
        );
        var payment = payments
            .authorize(
                "lab:" + key,
                new Authorization("merchant_demo", 12500, "USD")
            )
            .payment();
        UUID event = store.eventForPayment(payment.id());
        store.configure(event, request.scenario());
        UUID id = UUID.randomUUID();
        store.experiment(id, key, event, request.scenario());
        return new Experiment(id, event, request.scenario(), false);
    }
}
