package dev.atlas.eventing;

import java.util.*;
import org.springframework.stereotype.Service;

/** A discrete-event teaching model, separate from the durable PostgreSQL delivery worker. */
@Service
public class SimulationEngine {

    public enum Scenario {
        SAGA_COMPENSATION,
        CIRCUIT_BREAKER,
        RATE_LIMIT,
        BULKHEAD,
        CACHE_INVALIDATION,
        OPTIMISTIC_LOCK,
        PESSIMISTIC_LOCK,
        SYNC_ASYNC,
        LOST_RESPONSE,
        LEDGER_REPAIR,
        SLO_BUDGET,
        TIMEOUT_POLICY,
    }

    public record Event(long tick, String action, String evidence) {}

    public record Result(
        Scenario scenario,
        long seed,
        Map<String, Long> before,
        Map<String, Long> after,
        List<Event> timeline,
        boolean invariant,
        String explanation,
        String scope
    ) {}

    private record Scheduled(
        long tick,
        long sequence,
        Runnable work
    ) implements Comparable<Scheduled> {
        public int compareTo(Scheduled o) {
            int result = Long.compare(tick, o.tick);
            return result == 0 ? Long.compare(sequence, o.sequence) : result;
        }
    }

    private static class Model {

        long tick, sequence;
        final Map<String, Long> state = new TreeMap<>();
        final List<Event> timeline = new ArrayList<>();
        final PriorityQueue<Scheduled> queue = new PriorityQueue<>();

        void set(String name, long value) {
            state.put(name, value);
        }

        long get(String name) {
            return state.getOrDefault(name, 0L);
        }

        void add(String name, long amount) {
            set(name, get(name) + amount);
        }

        void at(long tick, Runnable work) {
            queue.add(new Scheduled(tick, sequence++, work));
        }

        void log(String action, String evidence) {
            timeline.add(new Event(tick, action, evidence));
        }

        void drain() {
            while (!queue.isEmpty()) {
                var e = queue.remove();
                tick = e.tick();
                e.work().run();
                if (timeline.size() > 500) throw new IllegalStateException(
                    "Simulation event bound"
                );
            }
        }
    }

    public Result run(Scenario scenario, long seed) {
        var m = new Model();
        String explanation;
        java.util.function.BooleanSupplier invariant;
        switch (scenario) {
            case SAGA_COMPENSATION -> {
                m.set("merchantBalance", 1000);
                m.set("reserved", 0);
                m.at(1, () -> {
                    m.add("merchantBalance", -100);
                    m.set("reserved", 100);
                    m.log(
                        "RESERVE",
                        "Step 1 commits a reservation in the model."
                    );
                });
                m.at(2, () -> {
                    m.set("fulfillmentFailed", 1);
                    m.log(
                        "FULFILLMENT_FAILED",
                        "Step 2 fails; the workflow is partially complete."
                    );
                });
                m.at(3, () -> {
                    if (m.get("reserved") == 100) {
                        m.add("merchantBalance", 100);
                        m.set("reserved", 0);
                        m.set("compensated", 1);
                    }
                    m.log(
                        "COMPENSATE",
                        "Compensation returns the reservation; it is not a cross-service database rollback."
                    );
                });
                m.at(4, () -> {
                    if (m.get("reserved") == 0) m.log(
                        "DUPLICATE_COMPENSATION",
                        "Already compensated; no second credit."
                    );
                });
                invariant = () ->
                    m.get("merchantBalance") == 1000 && m.get("reserved") == 0;
                explanation =
                    "Persist step IDs and idempotent compensations in a real saga. Compensation can fail and needs its own recovery policy.";
            }
            case CIRCUIT_BREAKER -> {
                m.set("consecutiveFailures", 0);
                m.set("openUntil", 0);
                for (int i = 1; i <= 9; i++) {
                    int request = i;
                    m.at(i, () -> {
                        if (m.get("openUntil") > m.tick) {
                            m.add("rejected", 1);
                            m.log(
                                "OPEN_REJECT",
                                "Request " + request + " skips the dependency."
                            );
                            return;
                        }
                        if (request <= 3) {
                            m.add("dependencyCalls", 1);
                            m.add("consecutiveFailures", 1);
                            m.log(
                                "FAILURE",
                                "Dependency failure " +
                                    m.get("consecutiveFailures")
                            );
                            if (m.get("consecutiveFailures") == 3) {
                                m.set("openUntil", 8);
                                m.log(
                                    "OPEN",
                                    "Three consecutive failures; allow a probe at tick 8."
                                );
                            }
                        } else {
                            m.add("dependencyCalls", 1);
                            m.set("consecutiveFailures", 0);
                            m.set("openUntil", 0);
                            m.log(
                                request == 8
                                    ? "HALF_OPEN_SUCCESS"
                                    : "CLOSED_SUCCESS",
                                "Healthy response resets the breaker."
                            );
                        }
                    });
                }
                invariant = () ->
                    m.get("rejected") == 4 &&
                    m.get("dependencyCalls") == 5 &&
                    m.get("consecutiveFailures") == 0;
                explanation =
                    "A circuit breaker protects a failing dependency. A half-open probe must be bounded and coordinated per breaker, not shared globally without a reason.";
            }
            case RATE_LIMIT -> {
                m.set("tokens", 3);
                for (int i = 0; i < 6; i++) m.at(0, () -> {
                    if (m.get("tokens") > 0) {
                        m.add("tokens", -1);
                        m.add("accepted", 1);
                        m.log("ACCEPT", "One token consumed.");
                    } else {
                        m.add("rejected", 1);
                        m.log(
                            "LIMIT",
                            "No token; caller receives a retry-after boundary."
                        );
                    }
                });
                m.at(1, () -> {
                    m.set("tokens", Math.min(3, m.get("tokens") + 1));
                    m.log(
                        "REFILL",
                        "One token replenished after one logical tick."
                    );
                });
                invariant = () ->
                    m.get("accepted") == 3 &&
                    m.get("rejected") == 3 &&
                    m.get("tokens") == 1;
                explanation =
                    "Token buckets bound bursts. A per-process bucket does not enforce a global quota across replicas.";
            }
            case BULKHEAD -> {
                m.set("capacity", 2);
                for (int i = 0; i < 5; i++) m.at(0, () -> {
                    if (m.get("inFlight") < m.get("capacity")) {
                        m.add("inFlight", 1);
                        m.add("accepted", 1);
                        m.log(
                            "ACQUIRE",
                            "Dedicated dependency bulkhead acquires a slot."
                        );
                        m.at(3, () -> {
                            m.add("inFlight", -1);
                            m.add("completed", 1);
                            m.log(
                                "RELEASE",
                                "Dependency work completes and releases its slot."
                            );
                        });
                    } else {
                        m.add("rejected", 1);
                        m.log(
                            "REJECT",
                            "Saturated boundary does not consume unrelated capacity."
                        );
                    }
                });
                invariant = () ->
                    m.get("completed") == 2 &&
                    m.get("inFlight") == 0 &&
                    m.get("rejected") == 3;
                explanation =
                    "Bulkheads separate resource budgets. Rejected work needs an explicit caller policy rather than an unbounded waiting queue.";
            }
            case CACHE_INVALIDATION -> {
                m.set("dbVersion", 1);
                m.set("cacheVersion", 1);
                m.at(1, () -> {
                    m.set("dbVersion", 2);
                    m.log("WRITE", "Source of truth advances to version 2.");
                });
                m.at(2, () -> {
                    if (m.get("cacheVersion") != m.get("dbVersion")) m.set(
                        "staleReadDetected",
                        1
                    );
                    m.log(
                        "STALE_READ",
                        "A cached version 1 read is stale after the write."
                    );
                });
                m.at(3, () -> {
                    m.set("cacheVersion", 0);
                    m.log("INVALIDATE", "Evict after the committed update.");
                });
                m.at(4, () -> {
                    if (m.get("cacheVersion") == 0) {
                        m.set("cacheVersion", m.get("dbVersion"));
                        m.add("misses", 1);
                    }
                    m.log("REFILL", "Miss reads committed version 2.");
                });
                invariant = () ->
                    m.get("cacheVersion") == m.get("dbVersion") &&
                    m.get("staleReadDetected") == 1;
                explanation =
                    "Invalidation introduces a consistency window. Durable invalidation or bounded TTL may be required; this model does not guarantee delivery of an invalidation event.";
            }
            case OPTIMISTIC_LOCK -> {
                m.set("version", 0);
                for (int i = 0; i < 2; i++) m.at(1, () -> {
                    long expected = 0;
                    if (m.get("version") == expected) {
                        m.add("version", 1);
                        m.add("writes", 1);
                        m.log(
                            "COMPARE_AND_SET",
                            "UPDATE ... WHERE version=0 affects one row."
                        );
                    } else {
                        m.add("conflicts", 1);
                        m.log(
                            "CONFLICT",
                            "Stale version affects zero rows; caller must re-read intent."
                        );
                    }
                });
                invariant = () ->
                    m.get("writes") == 1 && m.get("conflicts") == 1;
                explanation =
                    "The architecture editor uses this optimistic version invariant in PostgreSQL. Retrying blindly can overwrite a user's intention.";
            }
            case PESSIMISTIC_LOCK -> {
                m.set("available", 1);
                m.at(1, () -> {
                    m.set("locked", 1);
                    m.log(
                        "LOCK",
                        "Transaction A acquires the payment row lock."
                    );
                });
                m.at(2, () ->
                    m.log(
                        "WAIT",
                        "Transaction B cannot inspect mutable state before A releases its lock."
                    )
                );
                m.at(3, () -> {
                    m.set("available", 0);
                    m.add("captures", 1);
                    m.set("locked", 0);
                    m.log("COMMIT", "A captures and releases the lock.");
                });
                m.at(4, () -> {
                    if (m.get("available") == 0) m.add("conflicts", 1);
                    m.log(
                        "RECHECK",
                        "B rechecks the committed state and rejects capture."
                    );
                });
                invariant = () ->
                    m.get("captures") == 1 && m.get("conflicts") == 1;
                explanation =
                    "Real payment integration tests exercise PostgreSQL FOR UPDATE. This logical model explains the ordering; it is not a lock-performance measurement.";
            }
            case SYNC_ASYNC -> {
                m.set("writeVersion", 0);
                m.set("readVersion", 0);
                m.at(1, () -> {
                    m.set("writeVersion", 1);
                    m.log(
                        "WRITE_COMMIT",
                        "Command succeeds before asynchronous projection catches up."
                    );
                });
                m.at(2, () -> {
                    m.set("staleReads", 1);
                    m.log(
                        "READ",
                        "Projection still returns version 0: eventual consistency window."
                    );
                });
                m.at(4, () -> {
                    m.set("readVersion", 1);
                    m.log("PROJECT", "Queued event updates the read model.");
                });
                invariant = () -> m.get("writeVersion") == m.get("readVersion");
                explanation =
                    "Synchronous completion can imply a committed write without implying an updated asynchronous read model. Expose operation status when users need read-your-writes behavior.";
            }
            case LOST_RESPONSE -> {
                var responses = new HashMap<String, Long>();
                m.set("effects", 0);
                m.at(1, () -> {
                    responses.put("intent-1", 42L);
                    m.add("effects", 1);
                    m.log(
                        "COMMIT",
                        "Response and business effect commit together; HTTP response is lost."
                    );
                });
                m.at(2, () -> {
                    if (responses.containsKey("intent-1")) {
                        m.set("replayedId", responses.get("intent-1"));
                        m.log(
                            "REPLAY",
                            "Retry with the same intent returns original ID 42."
                        );
                    } else m.add("effects", 1);
                });
                invariant = () ->
                    m.get("effects") == 1 && m.get("replayedId") == 42;
                explanation =
                    "A lost response is an unknown outcome, not proof of rollback. The real payment API stores canonical intent fingerprints and immutable responses in its database transaction.";
            }
            case LEDGER_REPAIR -> {
                m.set("debits", 100);
                m.set("credits", 80);
                m.at(1, () -> {
                    m.set("imbalance", m.get("debits") - m.get("credits"));
                    m.log(
                        "RECONCILE",
                        "Injected unbalanced model detected: debit 100, credit 80, discrepancy 20."
                    );
                });
                m.at(2, () -> {
                    m.add("credits", m.get("imbalance"));
                    m.set("repairEntries", 1);
                    m.log(
                        "CORRECT",
                        "An explicit educational adjustment restores equality; preserve the original evidence."
                    );
                });
                invariant = () -> m.get("debits") == m.get("credits");
                explanation =
                    "Real ATLAS postings pair debit and credit structurally. Its database reconciliation also detects mismatches against payment state. This isolated model intentionally allows a one-sided corruption to teach detection; it does not mutate payment records.";
            }
            case SLO_BUDGET -> {
                var random = new Random(seed);
                m.set("requests", 100);
                m.set("targetPercent", 99);
                for (int i = 0; i < 100; i++) {
                    int latency = 10 + random.nextInt(200);
                    m.at(i, () -> {
                        if (latency > 100) m.add("bad", 1);
                        if (m.tick % 20 == 0) m.log(
                            "SAMPLE",
                            "Generated logical latency " +
                                latency +
                                " ticks; budget is 100 ticks."
                        );
                    });
                }
                m.at(101, () -> {
                    m.set("budgetRemaining", 1 - m.get("bad"));
                    m.log(
                        "BUDGET",
                        "99% objective allows one bad request per 100; calculate burn against the observation window."
                    );
                });
                invariant = () ->
                    m.get("bad") <= 100 &&
                    m.get("budgetRemaining") == 1 - m.get("bad");
                explanation =
                    "The seeded latency distribution is synthetic, not benchmark evidence. SLOs need a real workload, observation window and user-visible definition of failure.";
            }
            case TIMEOUT_POLICY -> {
                m.set("deadline", 2);
                m.set("dependencyLatency", 4);
                m.at(2, () -> {
                    m.set("callerTimedOut", 1);
                    m.log(
                        "TIMEOUT",
                        "Caller deadline expires; dependency outcome remains unknown."
                    );
                });
                m.at(4, () -> {
                    m.set("dependencyCommitted", 1);
                    m.log(
                        "LATE_COMMIT",
                        "Dependency completes after the caller stopped waiting."
                    );
                });
                invariant = () ->
                    m.get("callerTimedOut") == 1 &&
                    m.get("dependencyCommitted") == 1;
                explanation =
                    "Cancellation does not undo a remote commit. Use idempotency and status checks; budget dependency deadlines within the overall request deadline.";
            }
            default -> throw new IllegalArgumentException(
                "Unsupported scenario"
            );
        }
        var before = Map.copyOf(m.state);
        m.drain();
        return new Result(
            scenario,
            seed,
            before,
            Map.copyOf(m.state),
            List.copyOf(m.timeline),
            invariant.getAsBoolean(),
            explanation,
            "Deterministic discrete-event educational model. Ticks and generated latency are synthetic; no payment database mutations or measured throughput claims."
        );
    }
}
