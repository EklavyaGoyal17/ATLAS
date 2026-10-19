package dev.atlas.intelligence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.atlas.workspace.Documents;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryArchitectureTest {

    @TempDir
    Path root;

    private RepositoryArchitecture.Report report(Map<String, String> files) {
        var sources = files
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e ->
                new RepositoryIndex.Source(
                    e.getKey(),
                    "java",
                    e.getValue().split("\\R", -1).length,
                    e.getValue(),
                    List.of()
                )
            )
            .toList();
        return RepositoryArchitecture.analyze(
            new RepositoryIndex.Snapshot(
                UUID.randomUUID(),
                sources,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                0
            )
        );
    }

    @Test
    void crossPackageRecordsAndArraysHaveEvidenceAndExcludeSelfReferences() {
        var result = report(
            Map.of(
                "A.java",
                "package p; import q.B; class A {\n B[] b;\n A self;\n}",
                "B.java",
                "package q; import p.A; record B(A a) {}"
            )
        );
        assertThat(result.types())
            .hasSize(2)
            .allSatisfy(t -> {
                assertThat(t.fanIn()).isEqualTo(1);
                assertThat(t.fanOut()).isEqualTo(1);
            });
        assertThat(result.relationships())
            .hasSize(2)
            .anyMatch(e -> e.path().equals("A.java") && e.line() == 2);
        assertThat(result.cycles()).hasSize(1);
        assertThat(result.cycles().getFirst().members()).hasSize(2);
        assertThat(result.cycles().getFirst().evidence()).hasSize(2);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void explicitExternalImportsCannotBindToAnInternalNamesake() {
        var result = report(
            Map.of(
                "A.java",
                "package p; import outside.Target; class A { Target target; }",
                "Target.java",
                "package p; class Target { A owner; }"
            )
        );
        assertThat(result.relationships())
            .hasSize(1)
            .allMatch(e -> e.from().startsWith("p.Target@"));
        assertThat(result.cycles()).isEmpty();
        assertThat(result.unresolvedFields()).isEqualTo(1);
    }

    @Test
    void packagesAndExplicitImportsDisambiguateSameSimpleTypeNames() {
        var result = report(
            Map.of(
                "One.java",
                "package one; class Target {}",
                "Two.java",
                "package two; class Target {}",
                "Owner.java",
                "package p; import two.Target; class Owner { Target imported; one.Target qualified; }"
            )
        );
        assertThat(result.relationships())
            .hasSize(2)
            .anyMatch(e -> e.to().startsWith("one.Target@"))
            .anyMatch(e -> e.to().startsWith("two.Target@"));
    }

    @Test
    void nestedMembersResolveButTypeParametersWildcardsAndInheritedAmbiguityDoNot() {
        var result = report(
            Map.of(
                "Outer.java",
                "package p; class Outer { class Left { Right right; } class Right { Left left; } class Ambiguous extends Missing { Right unknown; } }",
                "Generic.java",
                "package p; class Generic<T> { T value; } class T {}",
                "Other.java",
                "package x; import p.*; class Other { T value; }",
                "Inherited.java",
                "package p; class Inherited extends Missing { T uncertain; p.T explicit; }"
            )
        );
        assertThat(result.cycles()).hasSize(1);
        assertThat(result.cycles().getFirst().members()).allMatch(id ->
            id.startsWith("p.Outer.")
        );
        assertThat(result.relationships()).hasSize(3);
        assertThat(result.unresolvedFields()).isEqualTo(4);
    }

    @Test
    void duplicateQualifiedDeclarationsNeverBecomeGraphTargets() {
        var result = report(
            Map.of(
                "One.java",
                "package p; class Duplicate {}",
                "Two.java",
                "package p; class Duplicate {}",
                "Owner.java",
                "package p; class Owner { Duplicate unknown; }"
            )
        );
        assertThat(result.types()).hasSize(1);
        assertThat(result.relationships()).isEmpty();
        assertThat(result.unresolvedFields()).isEqualTo(1);
    }

    @Test
    void importedNestedQualifiersResolveWhileStaticImportsAndRecordInheritanceRemainUnknown() {
        var result = report(
            Map.of(
                "Outer.java",
                "package external; class Outer { static class Nested {} }",
                "Owner.java",
                "package p; import external.Outer; class Owner { Outer.Nested nested; }",
                "Static.java",
                "package p; import static external.Missing.Target; class Static { Target unknown; } class Target {}",
                "Wildcard.java",
                "package p; import static external.Missing.*; class Wildcard { Target known; }",
                "Record.java",
                "package p; record Sample(Target uncertain) implements Missing {}",
                "Enum.java",
                "package p; enum Choice implements Missing { ONE; Target uncertain; }"
            )
        );
        assertThat(result.relationships())
            .hasSize(2)
            .anyMatch(e -> e.to().startsWith("external.Outer.Nested@"))
            .anyMatch(
                e ->
                    e.from().startsWith("p.Wildcard@") &&
                    e.to().startsWith("p.Target@")
            );
        assertThat(result.unresolvedFields()).isEqualTo(3);
    }

    @Test
    void couplingCountsDistinctNeighboursInsteadOfRepeatedFields() {
        var code = new StringBuilder(
            "package p; class Hub { T0 first; T0 duplicate;"
        );
        for (int i = 1; i < 5; i++) code.append("T")
            .append(i)
            .append(" field")
            .append(i)
            .append(";");
        code.append("}");
        for (int i = 0; i < 5; i++) code.append("class T")
            .append(i)
            .append("{} class Caller")
            .append(i)
            .append("{ Hub hub; }");
        var result = report(Map.of("Graph.java", code.toString()));
        assertThat(result.hotspots()).hasSize(1);
        assertThat(result.hotspots().getFirst().typeId()).startsWith("p.Hub@");
        assertThat(result.hotspots().getFirst().fanIn()).isEqualTo(5);
        assertThat(result.hotspots().getFirst().fanOut()).isEqualTo(5);
        assertThat(result.types())
            .filteredOn(t -> t.name().equals("p.T0"))
            .allMatch(t -> t.fanIn() == 1);
    }

    @Test
    void malformedAndWithheldSourceCannotSupplyRelationships() {
        var result = report(
            Map.of(
                "Good.java",
                "class Good {}",
                "Broken.java",
                "class Broken {",
                "Withheld.java",
                "[File withheld: private key material detected]"
            )
        );
        assertThat(result.skippedFiles()).isEqualTo(2);
        assertThat(result.types())
            .hasSize(1)
            .allMatch(t -> t.name().equals("Good"));
    }

    @Test
    void typeAndReferenceBudgetsAreExplicitWithoutInflatingCoupling() {
        var code = new StringBuilder();
        for (int i = 0; i < 1001; i++) code.append("class T")
            .append(i)
            .append(" {}\n");
        var limited = report(Map.of("Types.java", code.toString()));
        assertThat(limited.types()).hasSize(1000);
        assertThat(limited.truncated()).isTrue();
        var fields = new StringBuilder("class Owner {\n");
        for (int i = 0; i < 3001; i++) fields
            .append("Target field")
            .append(i)
            .append(";\n");
        fields.append("} class Target {}");
        var references = report(Map.of("Fields.java", fields.toString()));
        assertThat(references.relationships()).hasSize(3000);
        assertThat(references.truncated()).isTrue();
        assertThat(references.hotspots()).isEmpty();
    }

    @Test
    void seededGraphsMatchAnIndependentReachabilityOracle() {
        var random = new Random(7531);
        for (int seed = 0; seed < 40; seed++) {
            int size = 12;
            boolean[][] reachable = new boolean[size][size];
            var code = new StringBuilder("package g;\n");
            for (int from = 0; from < size; from++) {
                code.append("class N").append(from).append(" {\n");
                reachable[from][from] = true;
                for (int to = 0; to < size; to++) if (random.nextInt(5) == 0) {
                    reachable[from][to] = true;
                    code.append("N")
                        .append(to)
                        .append(" ref")
                        .append(to)
                        .append(";\n");
                }
                code.append("}\n");
            }
            for (int via = 0; via < size; via++) for (
                int from = 0;
                from < size;
                from++
            ) for (int to = 0; to < size; to++) reachable[from][to] |=
                reachable[from][via] && reachable[via][to];
            var expected = new HashSet<Set<String>>();
            for (int from = 0; from < size; from++) {
                var group = new TreeSet<String>();
                for (int to = 0; to < size; to++) if (
                    reachable[from][to] && reachable[to][from]
                ) group.add("g.N" + to);
                if (group.size() > 1) expected.add(group);
            }
            var result = report(Map.of("Graph.java", code.toString()));
            var names = new HashMap<String, String>();
            result.types().forEach(t -> names.put(t.id(), t.name()));
            var actual = new HashSet<Set<String>>();
            result
                .cycles()
                .forEach(c ->
                    actual.add(
                        new TreeSet<>(
                            c.members().stream().map(names::get).toList()
                        )
                    )
                );
            assertThat(actual).as("seed %s", seed).isEqualTo(expected);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(8)
    void onlyOneAnalysisRunsAndTheBudgetReleasesAfterFailure()
        throws Exception {
        var index = mock(RepositoryIndex.class);
        var snapshot = mock(RepositoryIndex.Snapshot.class);
        when(index.current()).thenReturn(snapshot);
        when(snapshot.id()).thenReturn(UUID.randomUUID());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(snapshot.files()).thenAnswer(call -> {
            entered.countDown();
            release.await();
            return List.of();
        });
        var architecture = new RepositoryArchitecture(index);
        try (
            var executor =
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        ) {
            var first = executor.submit(architecture::report);
            try {
                assertThat(
                    entered.await(2, java.util.concurrent.TimeUnit.SECONDS)
                ).isTrue();
                assertThatThrownBy(architecture::report)
                    .isInstanceOf(dev.atlas.platform.ApiException.class)
                    .hasMessageContaining("at a time");
            } finally {
                release.countDown();
            }
            first.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        when(snapshot.files())
            .thenThrow(new IllegalStateException("controlled failure"))
            .thenReturn(List.of());
        assertThatThrownBy(architecture::report).hasMessageContaining(
            "controlled failure"
        );
        assertThat(architecture.report().types()).isEmpty();
    }

    @Test
    void analysisRequiresAnApprovedIndexAndDoesNotSurviveDeletion()
        throws Exception {
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve("A.java"), "class A {}");
        var index = new RepositoryIndex(root.toString(), mock(Documents.class));
        var architecture = new RepositoryArchitecture(index);
        assertThatThrownBy(architecture::report).hasMessageContaining(
            "Approve"
        );
        var snapshot = index.index(".", true);
        assertThat(architecture.report().snapshotId()).isEqualTo(snapshot.id());
        index.clear();
        assertThatThrownBy(architecture::report).hasMessageContaining(
            "Approve"
        );
    }
}
