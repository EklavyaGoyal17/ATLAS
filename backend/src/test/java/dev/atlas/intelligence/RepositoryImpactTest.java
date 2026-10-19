package dev.atlas.intelligence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.atlas.platform.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RepositoryImpactTest {

    private final UUID snapshotId = UUID.randomUUID();

    private RepositoryArchitecture.Declaration type(
        String name,
        String path,
        int line
    ) {
        return new RepositoryArchitecture.Declaration(
            name + "@" + path + ":" + line,
            name,
            path,
            line,
            0,
            0
        );
    }

    private RepositoryArchitecture.Relationship edge(
        RepositoryArchitecture.Declaration from,
        RepositoryArchitecture.Declaration to,
        int line
    ) {
        return new RepositoryArchitecture.Relationship(
            from.id(),
            to.id(),
            from.path(),
            line
        );
    }

    private RepositoryArchitecture.Report graph(
        List<RepositoryArchitecture.Declaration> types,
        List<RepositoryArchitecture.Relationship> edges
    ) {
        return new RepositoryArchitecture.Report(
            snapshotId,
            types,
            edges,
            List.of(),
            List.of(),
            3,
            2,
            false,
            List.of("Declaration references only.")
        );
    }

    @Test
    void directAndTransitiveDependantsCarryTheShortestSourceEvidencedPath() {
        var store = type("p.Store", "Store.java", 2);
        var service = type("p.Service", "Service.java", 4);
        var controller = type("p.Controller", "Controller.java", 6);
        var outer = type("p.Outer", "Outer.java", 8);
        var unrelated = type("p.Unrelated", "Unrelated.java", 1);
        var serviceToStore = edge(service, store, 12);
        var controllerToService = edge(controller, service, 18);
        var controllerToStore = edge(controller, store, 19);
        var outerToController = edge(outer, controller, 21);
        var result = RepositoryImpact.analyze(
            graph(
                List.of(store, service, controller, outer, unrelated),
                List.of(
                    serviceToStore,
                    controllerToService,
                    controllerToStore,
                    outerToController
                )
            ),
            List.of("Store.java")
        );
        assertThat(result.snapshotId()).isEqualTo(snapshotId);
        assertThat(result.seeds()).containsExactly(store);
        assertThat(result.totalAffected()).isEqualTo(3);
        assertThat(result.affected())
            .extracting(i -> i.type().name())
            .containsExactly("p.Controller", "p.Service", "p.Outer");
        var direct = result.affected().getFirst();
        assertThat(direct.distance()).isEqualTo(1);
        assertThat(direct.changedTypeId()).isEqualTo(store.id());
        assertThat(direct.path()).containsExactly(controllerToStore);
        var transitive = result.affected().getLast();
        assertThat(transitive.distance()).isEqualTo(2);
        assertThat(transitive.path()).containsExactly(
            outerToController,
            controllerToStore
        );
        assertThat(result.unresolvedFields()).isEqualTo(3);
        assertThat(result.skippedFiles()).isEqualTo(2);
        assertThat(result.graphTruncated()).isFalse();
        assertThat(result.resultsTruncated()).isFalse();
        assertThat(result.limitations()).isNotEmpty();
    }

    @Test
    void cyclesDoNotIncludeChangedDeclarationsOrProduceRepeatedResults() {
        var a = type("p.A", "A.java", 1);
        var b = type("p.B", "B.java", 2);
        var c = type("p.C", "C.java", 3);
        var result = RepositoryImpact.analyze(
            graph(
                List.of(a, b, c),
                List.of(
                    edge(a, b, 10),
                    edge(b, a, 11),
                    edge(c, b, 12),
                    edge(c, b, 13)
                )
            ),
            List.of("A.java", "A.java")
        );
        assertThat(result.selectedPaths()).containsExactly("A.java");
        assertThat(result.seeds()).containsExactly(a);
        assertThat(result.totalAffected()).isEqualTo(2);
        assertThat(result.affected())
            .extracting(i -> i.type().id())
            .containsExactly(b.id(), c.id());
        assertThat(result.affected())
            .extracting(RepositoryImpact.Impact::distance)
            .containsExactly(1, 2);
    }

    @Test
    void allDeclarationsInSelectedFilesAreSeedsAndNearestOfMultipleChangesWins() {
        var a = type("p.A", "Changed.java", 1);
        var b = type("p.B", "Changed.java", 4);
        var far = type("p.Far", "Other.java", 1);
        var dependant = type("p.Dependant", "Dependant.java", 1);
        var isolated = type("p.Isolated", "Isolated.java", 1);
        var result = RepositoryImpact.analyze(
            graph(
                List.of(a, b, far, dependant, isolated),
                List.of(
                    edge(dependant, far, 3),
                    edge(far, a, 7),
                    edge(dependant, b, 9),
                    edge(a, b, 2)
                )
            ),
            List.of("Changed.java", "Isolated.java")
        );
        assertThat(result.seeds()).containsExactlyInAnyOrder(a, b, isolated);
        assertThat(result.affected()).hasSize(2);
        var found = result
            .affected()
            .stream()
            .filter(i -> i.type().equals(dependant))
            .findFirst()
            .orElseThrow();
        assertThat(found.distance()).isEqualTo(1);
        assertThat(found.changedTypeId()).isEqualTo(b.id());
        assertThat(found.path()).containsExactly(edge(dependant, b, 9));
    }

    @Test
    void qualifiedIdentitySeparatesSameSimpleNamesAndNonJavaPathsRemainExplicit() {
        var one = type("one.Target", "one/Target.java", 1);
        var two = type("two.Target", "two/Target.java", 1);
        var owner = type("p.Owner", "Owner.java", 2);
        var result = RepositoryImpact.analyze(
            graph(List.of(one, two, owner), List.of(edge(owner, two, 17))),
            List.of("one/Target.java", "frontend/main.tsx", "docs/guide.md")
        );
        assertThat(result.seeds()).containsExactly(one);
        assertThat(result.affected()).isEmpty();
        assertThat(result.unrepresentedPaths()).containsExactlyInAnyOrder(
            "frontend/main.tsx",
            "docs/guide.md"
        );
        assertThat(result.totalAffected()).isZero();
    }

    @Test
    void emptyGraphAndInheritedTruncationCannotImplyCompleteCoverage() {
        var source = new RepositoryArchitecture.Report(
            snapshotId,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            5,
            4,
            true,
            List.of("Graph budget reached.")
        );
        var result = RepositoryImpact.analyze(
            source,
            List.of("Broken.java", "notes.md")
        );
        assertThat(result.seeds()).isEmpty();
        assertThat(result.affected()).isEmpty();
        assertThat(result.unrepresentedPaths()).containsExactlyInAnyOrder(
            "Broken.java",
            "notes.md"
        );
        assertThat(result.graphTruncated()).isTrue();
        assertThat(result.unresolvedFields()).isEqualTo(5);
        assertThat(result.skippedFiles()).isEqualTo(4);
    }

    @Test
    void displayBudgetRetainsTotalAffectedAndDeterministicOrdering() {
        var target = type("p.Target", "Target.java", 1);
        var types = new ArrayList<RepositoryArchitecture.Declaration>();
        var edges = new ArrayList<RepositoryArchitecture.Relationship>();
        types.add(target);
        for (int i = 204; i >= 0; i--) {
            var dependant = type(
                "p.D" + String.format("%03d", i),
                "D" + i + ".java",
                1
            );
            types.add(dependant);
            edges.add(edge(dependant, target, i + 2));
        }
        var result = RepositoryImpact.analyze(
            graph(types, edges),
            List.of("Target.java")
        );
        assertThat(result.totalAffected()).isEqualTo(205);
        assertThat(result.affected()).hasSize(200);
        assertThat(result.affected())
            .extracting(i -> i.type().id())
            .isSorted();
        assertThat(result.affected().getFirst().type().name()).isEqualTo(
            "p.D000"
        );
        assertThat(result.affected().getLast().type().name()).isEqualTo(
            "p.D199"
        );
        assertThat(result.resultsTruncated()).isTrue();
    }

    @Test
    void longPathBudgetRetainsRealDistanceAndAContiguousSourcePrefix() {
        var types = new ArrayList<RepositoryArchitecture.Declaration>();
        var edges = new ArrayList<RepositoryArchitecture.Relationship>();
        for (int i = 0; i <= 25; i++) types.add(
            type("p.N" + i, "N" + i + ".java", 1)
        );
        for (int i = 1; i <= 25; i++) edges.add(
            edge(types.get(i), types.get(i - 1), i + 10)
        );
        var result = RepositoryImpact.analyze(
            graph(types, edges),
            List.of("N0.java")
        );
        var farthest = result
            .affected()
            .stream()
            .filter(i -> i.type().equals(types.get(25)))
            .findFirst()
            .orElseThrow();
        assertThat(farthest.distance()).isEqualTo(25);
        assertThat(farthest.changedTypeId()).isEqualTo(types.getFirst().id());
        assertThat(farthest.path()).hasSize(20);
        assertThat(farthest.pathTruncated()).isTrue();
        assertThat(farthest.path().getFirst().from()).isEqualTo(
            types.get(25).id()
        );
        for (int i = 1; i < farthest.path().size(); i++) assertThat(
            farthest
                .path()
                .get(i - 1)
                .to()
        ).isEqualTo(farthest.path().get(i).from());
        assertThat(result.resultsTruncated()).isTrue();
    }

    @Test
    void seededGraphsMatchIndependentAllPairsShortestDistancesAndSourcePaths() {
        var random = new Random(69127);
        for (int seed = 0; seed < 32; seed++) {
            int size = 14,
                infinity = 10000;
            var types = new ArrayList<RepositoryArchitecture.Declaration>();
            var edges = new ArrayList<RepositoryArchitecture.Relationship>();
            int[][] distances = new int[size][size];
            for (int from = 0; from < size; from++) {
                types.add(type("g.N" + from, "N" + from + ".java", from + 1));
                Arrays.fill(distances[from], infinity);
                distances[from][from] = 0;
            }
            for (int from = 0; from < size; from++) for (
                int to = 0;
                to < size;
                to++
            ) if (from != to && random.nextInt(6) == 0) {
                edges.add(
                    edge(types.get(from), types.get(to), from * size + to + 20)
                );
                distances[from][to] = 1;
            }
            for (int via = 0; via < size; via++) for (
                int from = 0;
                from < size;
                from++
            ) for (int to = 0; to < size; to++) distances[from][to] = Math.min(
                distances[from][to],
                distances[from][via] + distances[via][to]
            );
            Set<Integer> changed = new HashSet<>(
                List.of(
                    random.nextInt(size),
                    random.nextInt(size),
                    random.nextInt(size)
                )
            );
            var selected = changed
                .stream()
                .map(i -> types.get(i).path())
                .toList();
            var result = RepositoryImpact.analyze(
                graph(types, edges),
                selected
            );
            var actual = new HashMap<String, RepositoryImpact.Impact>();
            result
                .affected()
                .forEach(i ->
                    assertThat(actual.put(i.type().id(), i)).isNull()
                );
            int expectedCount = 0;
            for (int from = 0; from < size; from++) {
                int expected = infinity;
                for (int to : changed)
                    expected = Math.min(expected, distances[from][to]);
                var impact = actual.get(types.get(from).id());
                if (changed.contains(from) || expected == infinity) {
                    assertThat(impact)
                        .as("seed %s node %s", seed, from)
                        .isNull();
                    continue;
                }
                expectedCount++;
                assertThat(impact)
                    .as("seed %s node %s", seed, from)
                    .isNotNull();
                assertThat(impact.distance()).isEqualTo(expected);
                assertThat(impact.path())
                    .hasSize(expected)
                    .allMatch(edges::contains);
                assertThat(impact.pathTruncated()).isFalse();
                String cursor = types.get(from).id();
                for (var relationship : impact.path()) {
                    assertThat(relationship.from()).isEqualTo(cursor);
                    cursor = relationship.to();
                }
                assertThat(cursor).isEqualTo(impact.changedTypeId());
                assertThat(changed).anyMatch(i ->
                    types.get(i).id().equals(impact.changedTypeId())
                );
            }
            assertThat(result.totalAffected())
                .as("seed %s", seed)
                .isEqualTo(expectedCount);
            assertThat(actual).hasSize(expectedCount);
        }
    }

    private RepositoryIndex.Snapshot snapshot(UUID id) {
        return new RepositoryIndex.Snapshot(
            id,
            List.of(
                new RepositoryIndex.Source(
                    "A.java",
                    "java",
                    1,
                    "class A {}",
                    List.of()
                ),
                new RepositoryIndex.Source(
                    "notes.md",
                    "md",
                    1,
                    "Notes",
                    List.of()
                )
            ),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            0
        );
    }

    @Test
    void serviceUsesApprovedExactSnapshotAndAcceptsIndexedNonJavaFiles() {
        var index = mock(RepositoryIndex.class);
        var architecture = mock(RepositoryArchitecture.class);
        when(index.current()).thenReturn(snapshot(snapshotId));
        when(architecture.report()).thenReturn(
            graph(List.of(type("A", "A.java", 1)), List.of())
        );
        var result = new RepositoryImpact(index, architecture).report(
            snapshotId,
            List.of("notes.md", "A.java")
        );
        assertThat(result.snapshotId()).isEqualTo(snapshotId);
        assertThat(result.selectedPaths()).containsExactly(
            "A.java",
            "notes.md"
        );
        assertThat(result.unrepresentedPaths()).containsExactly("notes.md");
        verify(architecture).report();
    }

    @Test
    void staleSnapshotOrUnknownPathIsRejectedBeforeGraphAnalysis() {
        var index = mock(RepositoryIndex.class);
        var architecture = mock(RepositoryArchitecture.class);
        when(index.current()).thenReturn(snapshot(snapshotId));
        var service = new RepositoryImpact(index, architecture);
        assertThatThrownBy(() ->
            service.report(UUID.randomUUID(), List.of("A.java"))
        ).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code).isEqualTo("STALE_SNAPSHOT");
        });
        for (var invalid : List.of(
            "../A.java",
            "a.java",
            "./A.java",
            "missing.java",
            "C:\\A.java"
        )) {
            assertThatThrownBy(() ->
                service.report(snapshotId, List.of(invalid))
            ).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.code).isEqualTo("INVALID_IMPACT_PATH");
            });
        }
        verifyNoInteractions(architecture);
    }

    @Test
    void reimportBetweenValidationAndArchitectureAnalysisCannotMixSnapshots() {
        var index = mock(RepositoryIndex.class);
        var architecture = mock(RepositoryArchitecture.class);
        when(index.current()).thenReturn(snapshot(snapshotId));
        when(architecture.report()).thenReturn(
            new RepositoryArchitecture.Report(
                UUID.randomUUID(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                0,
                false,
                List.of()
            )
        );
        assertThatThrownBy(() ->
            new RepositoryImpact(index, architecture).report(
                snapshotId,
                List.of("A.java")
            )
        ).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code).isEqualTo("STALE_SNAPSHOT");
        });
        verify(architecture).report();
    }

    @Test
    void missingApprovalAndAnalysisFailureRemainVisibleWithoutPartialReport() {
        var index = mock(RepositoryIndex.class);
        var architecture = mock(RepositoryArchitecture.class);
        var service = new RepositoryImpact(index, architecture);
        when(index.current()).thenThrow(
            new ApiException(
                HttpStatus.CONFLICT,
                "NO_INDEX",
                "Approve an import first."
            )
        );
        assertThatThrownBy(() ->
            service.report(snapshotId, List.of("A.java"))
        ).isInstanceOfSatisfying(ApiException.class, e ->
            assertThat(e.code).isEqualTo("NO_INDEX")
        );
        verifyNoInteractions(architecture);
        doReturn(snapshot(snapshotId)).when(index).current();
        when(architecture.report()).thenThrow(
            new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "ANALYSIS_BUSY",
                "One analysis at a time."
            )
        );
        assertThatThrownBy(() ->
            service.report(snapshotId, List.of("A.java"))
        ).isInstanceOfSatisfying(ApiException.class, e ->
            assertThat(e.code).isEqualTo("ANALYSIS_BUSY")
        );
    }
}
