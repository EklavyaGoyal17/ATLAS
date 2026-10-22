package dev.atlas.intelligence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.atlas.observability.*;
import dev.atlas.platform.ApiException;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class IncidentBriefsTest {

    static final UUID ID = UUID.fromString(
        "430b3c81-e354-44f6-9df2-9d873427006e"
    );
    static final String BASE = "1".repeat(32);
    static final String AFFECTED = "2".repeat(32);
    static final String OTHER = "3".repeat(32);

    @Test
    void computesActualSampleMediansAndRetainedChildEvidence() {
        var summary = IncidentBriefs.summarize(
            evidence(
                samples(BASE, 7, 1, 3),
                samples(AFFECTED, 40, 20, 30),
                List.of(
                    span(
                        BASE,
                        1,
                        0,
                        400,
                        "UNSET",
                        "incident.slow_query.baseline"
                    ),
                    span(BASE, 2, 1, 3, "UNSET", "db.query"),
                    span(
                        AFFECTED,
                        3,
                        0,
                        800,
                        "ERROR",
                        "incident.slow_query.affected"
                    ),
                    span(AFFECTED, 4, 3, 75, "ERROR", "db.query")
                ),
                false
            )
        );
        assertThat(summary.id()).isEqualTo(ID);
        assertThat(summary.mode()).isEqualTo("DETERMINISTIC_SAVED_EVIDENCE");
        assertThat(summary.baseline().medianMs()).isEqualTo(3d);
        assertThat(summary.affected().medianMs()).isEqualTo(30d);
        assertThat(summary.affectedMinusBaselineMs()).isEqualTo(27d);
        assertThat(summary.retainedSpans()).isEqualTo(4);
        assertThat(summary.errorSpans()).isEqualTo(2);
        assertThat(summary.slowestChild().spanId()).isEqualTo(hex(4));
        assertThat(summary.slowestChild().name()).isEqualTo("db.query");
        assertThat(summary.slowestChild().durationMs()).isEqualTo(75);
        assertThat(summary.findings()).anySatisfy(f -> {
            assertThat(f.kind()).isEqualTo("SLOWEST_CHILD");
            assertThat(f.citations()).containsExactly(
                new IncidentBriefs.Citation(
                    AFFECTED,
                    hex(4),
                    "Slowest retained child"
                )
            );
        });
    }

    @Test
    void evenMedianUsesBothMiddleMeasurementsWithoutOverflow() {
        var summary = IncidentBriefs.summarize(
            evidence(
                samples(BASE, 2, 8, 4, 6),
                samples(AFFECTED, Double.MAX_VALUE, Double.MAX_VALUE),
                List.of(),
                false
            )
        );
        assertThat(summary.baseline().medianMs()).isEqualTo(5d);
        assertThat(summary.affected().medianMs()).isEqualTo(Double.MAX_VALUE);
        assertThat(summary.affectedMinusBaselineMs()).isFinite();
    }

    @Test
    void invalidDurationsDoNotContaminateMediansOrEraseFailureCounts() {
        var bad = List.of(
            new IncidentService.Sample(
                AFFECTED,
                Double.NaN,
                "CONTROLLED_FAILURE"
            ),
            new IncidentService.Sample(
                AFFECTED,
                Double.POSITIVE_INFINITY,
                "OK"
            ),
            new IncidentService.Sample(AFFECTED, -1, "CONTROLLED_FAILURE"),
            new IncidentService.Sample(AFFECTED, 12, "OK")
        );
        var summary = IncidentBriefs.summarize(
            evidence(samples(BASE, 0), bad, List.of(), false)
        );
        assertThat(summary.affected()).isEqualTo(
            new IncidentBriefs.Measurements(4, 1, 2, 12d)
        );
        assertThat(summary.baseline().medianMs()).isZero();
        assertThat(summary.affectedMinusBaselineMs()).isEqualTo(12d);
        assertThat(summary.limitations()).anyMatch(s ->
            s.contains("Non-finite or negative")
        );
    }

    @Test
    void absentMeasurementsAreUnavailableRatherThanZero() {
        var summary = IncidentBriefs.summarize(
            evidence(List.of(), samples(AFFECTED, Double.NaN), List.of(), false)
        );
        assertThat(summary.baseline().medianMs()).isNull();
        assertThat(summary.affected().medianMs()).isNull();
        assertThat(summary.affectedMinusBaselineMs()).isNull();
        assertThat(summary.findings()).noneMatch(f ->
            f.kind().equals("COMPARISON")
        );
        assertThat(summary.limitations()).anyMatch(s ->
            s.contains("No usable retained spans")
        );
    }

    @Test
    void citationsAndCountsExcludeUnrelatedInvalidAndDuplicateSpans() {
        var retained = span(AFFECTED, 1, 2, 10, "ERROR", "db.query");
        var summary = IncidentBriefs.summarize(
            evidence(
                samples(BASE, 1),
                samples(AFFECTED, 5),
                Arrays.asList(
                    retained,
                    retained,
                    span(OTHER, 2, 3, 10000, "ERROR", "db.query"),
                    span(AFFECTED, 3, 4, Double.NaN, "ERROR", "db.query"),
                    span(AFFECTED, 0, 4, 11, "ERROR", "db.query"),
                    null
                ),
                false
            )
        );
        assertThat(summary.retainedSpans()).isEqualTo(1);
        assertThat(summary.errorSpans()).isEqualTo(1);
        assertThat(summary.slowestChild().spanId()).isEqualTo(
            retained.spanId()
        );
        assertThat(
            summary
                .findings()
                .stream()
                .flatMap(f -> f.citations().stream())
        ).allSatisfy(c -> assertThat(c.traceId()).isIn(BASE, AFFECTED));
        assertThat(summary.limitations()).anyMatch(s ->
            s.contains("Unrelated, malformed, duplicate or excess")
        );
    }

    @Test
    void boundedFindingsAndCitationsStillReportActualRetainedErrorCount() {
        var many = IntStream.rangeClosed(1, 150)
            .mapToObj(i -> span(AFFECTED, i, 999, i, "ERROR", "db.query"))
            .toList();
        var baseline = IntStream.rangeClosed(1, 140)
            .mapToObj(i ->
                new IncidentService.Sample(
                    String.format("%032x", i + 500),
                    i,
                    "OK"
                )
            )
            .toList();
        var summary = IncidentBriefs.summarize(
            evidence(baseline, samples(AFFECTED, 4), many, true)
        );
        assertThat(summary.baseline().samples()).isEqualTo(128);
        assertThat(summary.retainedSpans()).isEqualTo(128);
        assertThat(summary.errorSpans()).isEqualTo(128);
        assertThat(summary.findings()).hasSizeLessThanOrEqualTo(5);
        assertThat(
            summary
                .findings()
                .stream()
                .flatMap(f -> f.citations().stream())
                .count()
        ).isLessThanOrEqualTo(10);
        assertThat(summary.findings())
            .filteredOn(f -> f.kind().equals("ERROR_SPANS"))
            .singleElement()
            .satisfies(f -> assertThat(f.citations()).hasSize(3));
        assertThat(summary.limitations()).anyMatch(s ->
            s.contains("truncated")
        );
        assertThat(summary.limitations()).anyMatch(s ->
            s.contains("first 128 baseline")
        );
    }

    @Test
    void unknownSpanNamesAndInvestigationFreeTextCannotLeakIntoSummary() {
        var summary = IncidentBriefs.summarize(
            evidence(
                samples(BASE, 2),
                samples(AFFECTED, 8),
                List.of(
                    span(
                        AFFECTED,
                        1,
                        2,
                        4,
                        "ERROR",
                        "PRIVATE secret request body"
                    )
                ),
                false
            )
        );
        assertThat(summary.slowestChild().name()).isEqualTo(
            "Other retained child span"
        );
        assertThat(summary.toString()).doesNotContain(
            "PRIVATE",
            "SECRET-HYPOTHESIS",
            "SECRET-VALIDATION",
            "SECRET-SCOPE"
        );
        assertThat(summary.limitations()).anyMatch(s ->
            s.contains("not AI analysis or proof of a production root cause")
        );
    }

    @Test
    void legacyEvidenceDoesNotReconstructMissingMeasurements() {
        var summary = IncidentBriefs.summarize(
            new IncidentArchive.Evidence(
                ID,
                false,
                null,
                List.of(),
                false,
                "PRIVATE LEGACY BODY"
            )
        );
        assertThat(summary.evidenceAvailable()).isFalse();
        assertThat(summary.scenario()).isNull();
        assertThat(summary.baseline().samples()).isZero();
        assertThat(summary.affected().medianMs()).isNull();
        assertThat(summary.findings()).isEmpty();
        assertThat(summary.limitations()).anyMatch(s ->
            s.contains("legacy investigation")
        );
        assertThat(summary.toString()).doesNotContain("PRIVATE LEGACY BODY");
    }

    @Test
    void invalidSampleTraceIdsCannotBecomeSourceCitations() {
        var summary = IncidentBriefs.summarize(
            evidence(
                samples("not-a-trace", 2),
                samples("0".repeat(32), 3),
                List.of(),
                false
            )
        );
        assertThat(summary.baseline().medianMs()).isEqualTo(2d);
        assertThat(
            summary
                .findings()
                .stream()
                .flatMap(f -> f.citations().stream())
        ).isEmpty();
    }

    @Test
    void missingAndDeletedInvestigationsPropagateTheArchiveNotFoundResult() {
        var archive = mock(IncidentArchive.class);
        var deleted = new ApiException(
            HttpStatus.NOT_FOUND,
            "INCIDENT_NOT_FOUND",
            "Investigation not found."
        );
        when(archive.get(ID)).thenThrow(deleted);
        assertThatThrownBy(() -> new IncidentBriefs(archive).get(ID)).isSameAs(
            deleted
        );
        verify(archive).get(ID);
        verifyNoMoreInteractions(archive);
    }

    @Test
    void controllerReadsExactlyTheRequestedSavedArchiveWithoutOtherRetrieval() {
        var archive = mock(IncidentArchive.class);
        when(archive.get(ID)).thenReturn(
            evidence(samples(BASE, 2), samples(AFFECTED, 3), List.of(), false)
        );
        var result = new AssistantIncidentController(
            new IncidentBriefs(archive)
        ).summary(ID);
        assertThat(result.id()).isEqualTo(ID);
        verify(archive).get(ID);
        verifyNoMoreInteractions(archive);
    }

    @Test
    void tiedSlowChildrenUseStableTraceThenSpanIdentityOrder() {
        var summary = IncidentBriefs.summarize(
            evidence(
                samples(BASE, 2),
                samples(AFFECTED, 3),
                List.of(
                    span(AFFECTED, 5, 9, 10, "UNSET", "db.query"),
                    span(BASE, 6, 9, 10, "UNSET", "db.query"),
                    span(BASE, 3, 9, 10, "UNSET", "db.query")
                ),
                false
            )
        );
        assertThat(summary.slowestChild().traceId()).isEqualTo(BASE);
        assertThat(summary.slowestChild().spanId()).isEqualTo(hex(3));
    }

    private static List<IncidentService.Sample> samples(
        String trace,
        double... durations
    ) {
        return Arrays.stream(durations)
            .mapToObj(n -> new IncidentService.Sample(trace, n, "OK"))
            .toList();
    }

    private static IncidentArchive.Evidence evidence(
        List<IncidentService.Sample> baseline,
        List<IncidentService.Sample> affected,
        List<Telemetry.Trace> spans,
        boolean truncated
    ) {
        return new IncidentArchive.Evidence(
            ID,
            true,
            new IncidentService.Investigation(
                ID,
                IncidentService.Scenario.SLOW_QUERY,
                baseline,
                affected,
                "SECRET-HYPOTHESIS",
                "SECRET-VALIDATION",
                "SECRET-SCOPE"
            ),
            spans,
            truncated,
            "SECRET-SCOPE"
        );
    }

    private static Telemetry.Trace span(
        String trace,
        int id,
        int parent,
        double ms,
        String status,
        String name
    ) {
        return new Telemetry.Trace(
            trace,
            hex(id),
            hex(parent),
            name,
            ms,
            status,
            "PRIVATE CORRELATION",
            Instant.EPOCH
        );
    }

    private static String hex(int value) {
        return String.format("%016x", value);
    }
}
