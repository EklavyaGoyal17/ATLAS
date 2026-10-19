package dev.atlas.intelligence;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class LocalInvestigatorTest {

    @Test
    void excludesFactsAndCallTargetsOutsideTheCitedLineRanges() {
        var snapshot = new RepositoryIndex.Snapshot(
            UUID.randomUUID(),
            List.of(),
            List.of(
                new RepositoryIndex.Symbol(
                    "Source.java",
                    10,
                    20,
                    "visible",
                    "endpoint",
                    ""
                ),
                new RepositoryIndex.Symbol(
                    "Source.java",
                    50,
                    60,
                    "uncited",
                    "transaction",
                    ""
                )
            ),
            List.of(),
            List.of(
                new RepositoryIndex.Call(
                    "Source.visible",
                    "Target.hidden",
                    "Source.java",
                    12,
                    "Target.java",
                    8,
                    "potential"
                )
            ),
            List.of(),
            Map.of(),
            0
        );
        var context = List.of(
            new ModelProvider.Context(
                "id",
                "Source.java",
                1,
                28,
                "source excerpt"
            )
        );
        var result = LocalInvestigator.explain(
            snapshot,
            "Review architecture",
            context
        );
        assertThat(result)
            .contains(
                "endpoint declaration: visible",
                "review questions, not measured findings"
            )
            .doesNotContain(
                "uncited",
                "Target.hidden",
                "Potential call:",
                "proxy invocation"
            );
    }
}
