package dev.atlas.intelligence;

import dev.atlas.observability.IncidentArchive;
import dev.atlas.observability.IncidentService;
import dev.atlas.observability.Telemetry;
import java.util.*;
import org.springframework.stereotype.Service;

/** Deterministic calculations over the immutable saved investigation, with no retrieval or model transport. */
@Service
public class IncidentBriefs {

    static final int MAX_SAMPLES = 128;

    public record Measurements(
        int samples,
        int measuredSamples,
        int failedSamples,
        Double medianMs
    ) {}

    public record Citation(String traceId, String spanId, String label) {}

    public record Finding(String kind, String text, List<Citation> citations) {}

    public record SpanEvidence(
        String traceId,
        String spanId,
        String parentId,
        String name,
        double durationMs,
        String status
    ) {}

    public record Summary(
        UUID id,
        String mode,
        boolean evidenceAvailable,
        String scenario,
        Measurements baseline,
        Measurements affected,
        Double affectedMinusBaselineMs,
        int retainedSpans,
        int errorSpans,
        SpanEvidence slowestChild,
        List<Finding> findings,
        List<String> limitations
    ) {}

    private final IncidentArchive archive;

    public IncidentBriefs(IncidentArchive archive) {
        this.archive = archive;
    }

    public Summary get(UUID id) {
        return summarize(archive.get(id));
    }

    static Summary summarize(IncidentArchive.Evidence evidence) {
        var limits = new ArrayList<String>();
        limits.add(
            "Deterministic summary of a controlled synthetic investigation, not AI analysis or proof of a production root cause. The configured injection is a hypothesis to validate, not an independently established diagnosis."
        );
        limits.add(
            "Only saved sample measurements and retained spans are used. Live telemetry may expire independently. Journal entries, postmortems, repository content and model providers are not consulted."
        );
        limits.add(
            "Retention is bounded to 128 spans and 128000 UTF-8 bytes per archived investigation. Error-span counts describe retained spans, not unique failed requests; child durations may overlap and are not added together. Citations show at most three baseline traces, three affected traces, three error spans and one slowest child; findings are limited to five."
        );
        var investigation = evidence.investigation();
        if (!evidence.available() || investigation == null) {
            limits.add(
                "Saved evidence is unavailable for this legacy investigation. Sample measurements and expired child spans cannot be reconstructed by this summary."
            );
            return new Summary(
                evidence.id(),
                "DETERMINISTIC_SAVED_EVIDENCE",
                false,
                null,
                measurements(List.of()),
                measurements(List.of()),
                null,
                0,
                0,
                null,
                List.of(),
                List.copyOf(limits)
            );
        }
        var baseline = samples(investigation.baseline());
        var affected = samples(investigation.affected());
        if (
            size(investigation.baseline()) > MAX_SAMPLES ||
            size(investigation.affected()) > MAX_SAMPLES
        ) limits.add(
            "Summary sample calculations are limited to the first 128 baseline and first 128 affected samples."
        );
        var allSamples = new ArrayList<IncidentService.Sample>(baseline);
        allSamples.addAll(affected);
        var allowed = new HashSet<String>();
        allSamples
            .stream()
            .map(IncidentService.Sample::traceId)
            .filter(IncidentBriefs::traceId)
            .forEach(allowed::add);
        var savedSpans =
            evidence.spans() == null
                ? List.<Telemetry.Trace>of()
                : evidence.spans();
        var distinctSpans = new LinkedHashMap<String, Telemetry.Trace>();
        savedSpans
            .stream()
            .limit(IncidentArchive.MAX_SPANS)
            .filter(Objects::nonNull)
            .filter(
                s ->
                    allowed.contains(s.traceId()) &&
                    spanId(s.spanId()) &&
                    !"0000000000000000".equals(s.spanId()) &&
                    duration(s.durationMs())
            )
            .forEach(s ->
                distinctSpans.putIfAbsent(s.traceId() + ":" + s.spanId(), s)
            );
        var spans = List.copyOf(distinctSpans.values());
        if (spans.size() != savedSpans.size()) limits.add(
            "Unrelated, malformed, duplicate or excess saved spans are excluded from this summary."
        );
        if (evidence.truncated()) limits.add(
            "The saved evidence was truncated to its retention budget. Missing spans may change the observed slowest child and error counts."
        );
        if (spans.isEmpty()) limits.add(
            "No usable retained spans are available. Sample trace IDs remain citations to the saved measurements; they do not imply child spans were retained."
        );
        var b = measurements(baseline);
        var a = measurements(affected);
        if (
            b.samples() != b.measuredSamples() ||
            a.samples() != a.measuredSamples()
        ) limits.add(
            "Non-finite or negative sample durations are excluded from medians. Sample and failure counts still include those saved samples."
        );
        Double delta =
            b.medianMs() == null || a.medianMs() == null
                ? null
                : a.medianMs() - b.medianMs();
        int errors = (int) spans
            .stream()
            .filter(s -> "ERROR".equals(s.status()))
            .count();
        var child = spans
            .stream()
            .filter(
                s ->
                    spanId(s.parentId()) &&
                    !"0000000000000000".equals(s.parentId())
            )
            .sorted(
                Comparator.comparingDouble(Telemetry.Trace::durationMs)
                    .reversed()
                    .thenComparing(Telemetry.Trace::traceId)
                    .thenComparing(Telemetry.Trace::spanId)
            )
            .findFirst()
            .orElse(null);
        var findings = new ArrayList<Finding>();
        findings.add(
            new Finding(
                "BASELINE",
                "Saved baseline sample measurements; median uses finite, nonnegative durations.",
                sampleCitations(baseline, "Baseline sample")
            )
        );
        findings.add(
            new Finding(
                "AFFECTED",
                "Saved affected sample measurements; failed samples have an outcome other than OK.",
                sampleCitations(affected, "Affected sample")
            )
        );
        if (delta != null) findings.add(
            new Finding(
                "COMPARISON",
                "Affected median minus baseline median, in milliseconds. This describes the controlled sample set and does not establish a general service latency or causal claim.",
                List.of()
            )
        );
        if (errors > 0) findings.add(
            new Finding(
                "ERROR_SPANS",
                "Retained spans marked ERROR. Multiple spans can belong to one request.",
                spans
                    .stream()
                    .filter(s -> "ERROR".equals(s.status()))
                    .limit(3)
                    .map(s ->
                        new Citation(s.traceId(), s.spanId(), "Error span")
                    )
                    .toList()
            )
        );
        SpanEvidence slowest =
            child == null
                ? null
                : new SpanEvidence(
                      child.traceId(),
                      child.spanId(),
                      child.parentId(),
                      knownName(child.name()),
                      child.durationMs(),
                      "ERROR".equals(child.status()) ? "ERROR" : "NON_ERROR"
                  );
        if (child != null) findings.add(
            new Finding(
                "SLOWEST_CHILD",
                "Longest-duration retained child span across baseline and affected traces. Its parent ID denotes a child even if the parent was not retained. Duration alone is not proof of a bottleneck or root cause.",
                List.of(
                    new Citation(
                        child.traceId(),
                        child.spanId(),
                        "Slowest retained child"
                    )
                )
            )
        );
        else limits.add(
            "No usable child span is retained; the summary cannot identify a slowest child."
        );
        return new Summary(
            evidence.id(),
            "DETERMINISTIC_SAVED_EVIDENCE",
            true,
            investigation.scenario() == null
                ? null
                : investigation.scenario().name(),
            b,
            a,
            delta,
            spans.size(),
            errors,
            slowest,
            List.copyOf(findings),
            List.copyOf(limits)
        );
    }

    private static List<IncidentService.Sample> samples(
        List<IncidentService.Sample> values
    ) {
        return values == null
            ? List.of()
            : values
                  .stream()
                  .limit(MAX_SAMPLES)
                  .filter(Objects::nonNull)
                  .toList();
    }

    private static Measurements measurements(
        List<IncidentService.Sample> samples
    ) {
        var values = samples
            .stream()
            .mapToDouble(IncidentService.Sample::durationMs)
            .filter(IncidentBriefs::duration)
            .sorted()
            .toArray();
        Double median =
            values.length == 0
                ? null
                : values.length % 2 == 1
                  ? values[values.length / 2]
                  : values[values.length / 2 - 1] / 2 +
                    values[values.length / 2] / 2;
        return new Measurements(
            samples.size(),
            values.length,
            (int) samples
                .stream()
                .filter(s -> !"OK".equals(s.outcome()))
                .count(),
            median
        );
    }

    private static List<Citation> sampleCitations(
        List<IncidentService.Sample> samples,
        String label
    ) {
        return samples
            .stream()
            .map(IncidentService.Sample::traceId)
            .filter(IncidentBriefs::traceId)
            .distinct()
            .limit(3)
            .map(id -> new Citation(id, null, label))
            .toList();
    }

    private static boolean duration(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    private static boolean traceId(String value) {
        return (
            value != null &&
            value.matches("[0-9a-f]{32}") &&
            !value.equals("0".repeat(32))
        );
    }

    private static boolean spanId(String value) {
        return value != null && value.matches("[0-9a-f]{16}");
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static String knownName(String value) {
        return value != null &&
            Set.of(
                "db.query",
                "db.pool.baseline",
                "db.pool.acquire",
                "db.pool.recovered",
                "queue.consume",
                "dependency.synthetic.deadline"
            ).contains(value)
            ? value
            : "Other retained child span";
    }
}
