package dev.atlas.intelligence;

import dev.atlas.platform.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** A bounded reverse dependency walk over one approved, redacted declaration graph. */
@Component
public class RepositoryImpact {

    public static final int MAX_RESULTS = 200,
        MAX_PATH_EDGES = 20;

    public record Impact(
        RepositoryArchitecture.Declaration type,
        int distance,
        String changedTypeId,
        List<RepositoryArchitecture.Relationship> path,
        boolean pathTruncated
    ) {
        public Impact {
            path = List.copyOf(path);
        }
    }

    public record Report(
        UUID snapshotId,
        List<String> selectedPaths,
        List<RepositoryArchitecture.Declaration> seeds,
        List<Impact> affected,
        int totalAffected,
        List<String> unrepresentedPaths,
        int unresolvedFields,
        int skippedFiles,
        boolean graphTruncated,
        boolean resultsTruncated,
        List<String> limitations
    ) {
        public Report {
            selectedPaths = List.copyOf(selectedPaths);
            seeds = List.copyOf(seeds);
            affected = List.copyOf(affected);
            unrepresentedPaths = List.copyOf(unrepresentedPaths);
            limitations = List.copyOf(limitations);
        }
    }

    private record Step(
        int distance,
        String changedTypeId,
        RepositoryArchitecture.Relationship edge
    ) {}

    private final RepositoryIndex index;
    private final RepositoryArchitecture architecture;

    public RepositoryImpact(
        RepositoryIndex index,
        RepositoryArchitecture architecture
    ) {
        this.index = index;
        this.architecture = architecture;
    }

    public Report report(UUID snapshotId, List<String> paths) {
        var snapshot = index.current();
        if (!snapshot.id().equals(snapshotId)) throw staleSnapshot();
        var available = new HashSet<String>();
        snapshot.files().forEach(file -> available.add(file.path()));
        for (var path : paths) {
            if (!available.contains(path)) throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_IMPACT_PATH",
                "Select exact file paths from the approved snapshot."
            );
        }
        var graph = architecture.report();
        if (!graph.snapshotId().equals(snapshotId)) throw staleSnapshot();
        return analyze(graph, paths);
    }

    private static ApiException staleSnapshot() {
        return new ApiException(
            HttpStatus.CONFLICT,
            "STALE_SNAPSHOT",
            "The approved snapshot changed. Refresh it before reviewing change impact."
        );
    }

    static Report analyze(
        RepositoryArchitecture.Report architecture,
        List<String> paths
    ) {
        var selected = new TreeSet<>(paths);
        var types = new TreeMap<String, RepositoryArchitecture.Declaration>();
        architecture.types().forEach(type -> types.put(type.id(), type));
        var seeds = types
            .values()
            .stream()
            .filter(type -> selected.contains(type.path()))
            .toList();
        var represented = new HashSet<String>();
        seeds.forEach(seed -> represented.add(seed.path()));
        var unrepresented = selected
            .stream()
            .filter(path -> !represented.contains(path))
            .toList();

        // Stable queue and edge order make equal-length alternatives reproducible.
        var incoming = new TreeMap<
            String,
            List<RepositoryArchitecture.Relationship>
        >();
        architecture
            .relationships()
            .stream()
            .sorted(
                Comparator.comparing(RepositoryArchitecture.Relationship::from)
                    .thenComparing(RepositoryArchitecture.Relationship::to)
                    .thenComparing(RepositoryArchitecture.Relationship::path)
                    .thenComparingInt(RepositoryArchitecture.Relationship::line)
            )
            .filter(
                edge ->
                    types.containsKey(edge.from()) &&
                    types.containsKey(edge.to())
            )
            .forEach(edge ->
                incoming
                    .computeIfAbsent(edge.to(), key -> new ArrayList<>())
                    .add(edge)
            );
        var steps = new HashMap<String, Step>();
        var queue = new ArrayDeque<String>();
        for (var seed : seeds) {
            steps.put(seed.id(), new Step(0, seed.id(), null));
            queue.addLast(seed.id());
        }
        while (!queue.isEmpty()) {
            var target = queue.removeFirst();
            var targetStep = steps.get(target);
            for (var edge : incoming.getOrDefault(target, List.of())) {
                if (steps.containsKey(edge.from())) continue;
                steps.put(
                    edge.from(),
                    new Step(
                        targetStep.distance() + 1,
                        targetStep.changedTypeId(),
                        edge
                    )
                );
                queue.addLast(edge.from());
            }
        }

        var affectedIds = steps
            .keySet()
            .stream()
            .filter(id -> steps.get(id).distance() > 0)
            .sorted(
                Comparator.<String>comparingInt(id ->
                    steps.get(id).distance()
                ).thenComparing(Comparator.naturalOrder())
            )
            .toList();
        var affected = new ArrayList<Impact>();
        boolean resultsTruncated = affectedIds.size() > MAX_RESULTS;
        for (var id : affectedIds.stream().limit(MAX_RESULTS).toList()) {
            var step = steps.get(id);
            var path = new ArrayList<RepositoryArchitecture.Relationship>();
            var cursor = id;
            while (
                path.size() < MAX_PATH_EDGES && steps.get(cursor).edge() != null
            ) {
                var edge = steps.get(cursor).edge();
                path.add(edge);
                cursor = edge.to();
            }
            boolean pathTruncated = step.distance() > path.size();
            resultsTruncated |= pathTruncated;
            affected.add(
                new Impact(
                    types.get(id),
                    step.distance(),
                    step.changedTypeId(),
                    path,
                    pathTruncated
                )
            );
        }
        return new Report(
            architecture.snapshotId(),
            new ArrayList<>(selected),
            seeds,
            affected,
            affectedIds.size(),
            unrepresented,
            architecture.unresolvedFields(),
            architecture.skippedFiles(),
            architecture.truncated(),
            resultsTruncated,
            List.of(
                "Candidates follow incoming Java field, array-element and record-component declaration references in the approved redacted snapshot.",
                "A candidate is a review lead. This graph does not establish runtime propagation, executed calls, test coverage or a defect.",
                "Only resolved types and relationships in the bounded architecture graph are considered; unresolved references and skipped files can hide candidates.",
                "Selected files without a resolved type are listed separately. Text, other languages and excluded or ambiguous Java declarations have no inferred graph impact.",
                "Distances count declaration edges. Each candidate includes one deterministic shortest path toward a selected type; alternative paths may exist.",
                "At most 200 candidates and 20 edges per returned path are shown; totalAffected counts all candidates in the bounded graph and distance retains the full shortest length.",
                "graphTruncated propagates the architecture flag, which can also indicate capped cycle or coupling displays; it does not identify which graph facts were omitted."
            )
        );
    }
}
