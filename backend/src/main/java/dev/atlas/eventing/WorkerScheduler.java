package dev.atlas.eventing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "atlas.worker.scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class WorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(
        WorkerScheduler.class
    );
    private final DeliveryService service;

    public WorkerScheduler(DeliveryService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${atlas.worker.interval-ms:1000}")
    public void poll() {
        try {
            service.step(new DeliveryModels.Step(null, 1), true);
        } catch (RuntimeException failure) {
            log.warn(
                "worker_poll_failed type={}",
                failure.getClass().getSimpleName()
            );
        }
    }
}
