package dev.atlas.eventing;

import static dev.atlas.eventing.DeliveryModels.*;

import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(
        DeliveryService.class
    );
    private final DeliveryStore store;
    private final TransactionTemplate transaction;

    public DeliveryService(
        DeliveryStore store,
        PlatformTransactionManager manager
    ) {
        this.store = store;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        transaction.setTimeout(10);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page events(int page, int size) {
        return store.events(page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(UUID id) {
        return store.detail(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Control control() {
        return store.control();
    }

    @Transactional
    public Control running(boolean value) {
        store.running(value);
        return store.control();
    }

    @Transactional
    public Detail redeliver(UUID id) {
        store.redeliver(id);
        return store.detail(id);
    }

    public StepResult step(Step request, boolean scheduled) {
        if (
            request.advanceTicks() < 1 || request.advanceTicks() > 16
        ) throw new dev.atlas.platform.ApiException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "INVALID_TICK_ADVANCE",
            "Advance 1–16 logical ticks."
        );
        var claim = Objects.requireNonNull(
            transaction.execute(status ->
                store.claim(
                    request.eventId(),
                    request.advanceTicks(),
                    scheduled
                )
            )
        );
        if (claim.isEmpty()) return new StepResult(
            control().tick(),
            null,
            "IDLE",
            "No event was ready. The clock advances only while stepping or running."
        );
        Claim item = claim.get();
        try {
            boolean applied = Boolean.TRUE.equals(
                transaction.execute(status -> {
                    if (
                        item.fault() == Fault.ALWAYS_FAIL
                    ) throw new ConsumerFailure();
                    boolean owns = store.apply(item);
                    if (
                        owns &&
                        item.attempt() == 1 &&
                        item.fault() == Fault.CRASH_BEFORE_COMMIT_ONCE
                    ) throw new SimulatedCrash();
                    return owns;
                })
            );
            if (!applied) return result(
                item,
                "FENCED",
                "Lease ownership changed; stale worker performed no effect."
            );
        } catch (SimulatedCrash crash) {
            transaction.executeWithoutResult(status ->
                store.log(
                    item.eventId(),
                    item.tick(),
                    item.attempt(),
                    "CRASH_BEFORE_COMMIT",
                    "Simulated interruption rolled back inbox and notification. Lease remains unacknowledged."
                )
            );
            return result(
                item,
                "CRASH_BEFORE_COMMIT",
                "Consumer transaction rolled back. Advance two ticks to reclaim the lease."
            );
        } catch (RuntimeException failure) {
            boolean recorded = Boolean.TRUE.equals(
                transaction.execute(status -> store.fail(item))
            );
            if (!recorded) return result(
                item,
                "FENCED",
                "Lease ownership changed; stale failure did not reschedule delivery."
            );
            log.warn(
                "consumer_attempt_failed eventId={} attempt={} type={}",
                item.eventId(),
                item.attempt(),
                failure.getClass().getSimpleName()
            );
            return result(
                item,
                item.attempt() >= 3 ? "DEAD_LETTERED" : "RETRY_SCHEDULED",
                "Consumer attempt failed; inspect the persisted delivery timeline."
            );
        }
        if (
            item.attempt() == 1 && item.fault() == Fault.CRASH_AFTER_COMMIT_ONCE
        ) {
            transaction.executeWithoutResult(status ->
                store.log(
                    item.eventId(),
                    item.tick(),
                    item.attempt(),
                    "CRASH_AFTER_COMMIT",
                    "Simulated interruption after consumer commit, before acknowledgement. Notification already exists."
                )
            );
            return result(
                item,
                "CRASH_AFTER_COMMIT",
                "Effect committed, acknowledgement missing. Recovery must suppress duplicate processing."
            );
        }
        boolean acknowledged = Boolean.TRUE.equals(
            transaction.execute(status -> store.acknowledge(item))
        );
        return result(
            item,
            acknowledged ? "DELIVERED" : "FENCED",
            acknowledged
                ? "Consumer committed and delivery acknowledged."
                : "Stale acknowledgement rejected by the lease token."
        );
    }

    private StepResult result(Claim claim, String outcome, String detail) {
        return new StepResult(claim.tick(), claim.eventId(), outcome, detail);
    }

    private static class SimulatedCrash extends RuntimeException {}

    private static class ConsumerFailure extends RuntimeException {}
}
