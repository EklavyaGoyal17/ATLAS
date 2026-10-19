package dev.atlas.intelligence;

import com.github.javaparser.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.type.Type;
import java.util.*;
import org.springframework.stereotype.Component;

/** A bounded declaration graph over the approved redacted snapshot; no compiler or execution. */
@Component
public class RepositoryArchitecture {

    public static final int MAX_TYPES = 1000,
        MAX_EDGES = 3000;

    public record Declaration(
        String id,
        String name,
        String path,
        int line,
        int fanIn,
        int fanOut
    ) {}

    public record Relationship(String from, String to, String path, int line) {}

    public record Cycle(
        List<String> members,
        List<Relationship> evidence,
        boolean evidenceTruncated
    ) {}

    public record Hotspot(
        String typeId,
        int fanIn,
        int fanOut,
        List<Relationship> evidence,
        boolean evidenceTruncated
    ) {}

    public record Report(
        UUID snapshotId,
        List<Declaration> types,
        List<Relationship> relationships,
        List<Cycle> cycles,
        List<Hotspot> hotspots,
        int unresolvedFields,
        int skippedFiles,
        boolean truncated,
        List<String> limitations
    ) {}

    private record Decl(
        String name,
        String path,
        TypeDeclaration<?> node,
        CompilationUnit unit
    ) {
        String id() {
            return name + "@" + path + ":" + line(node);
        }

        String pkg() {
            return unit
                .getPackageDeclaration()
                .map(p -> p.getNameAsString())
                .orElse("");
        }
    }

    private final RepositoryIndex index;
    private final java.util.concurrent.Semaphore gate =
        new java.util.concurrent.Semaphore(1);

    public RepositoryArchitecture(RepositoryIndex index) {
        this.index = index;
    }

    public Report report() {
        var snapshot = index.current();
        if (!gate.tryAcquire()) throw new dev.atlas.platform.ApiException(
            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
            "ANALYSIS_BUSY",
            "One bounded architecture analysis can run at a time."
        );
        try {
            return analyze(snapshot);
        } finally {
            gate.release();
        }
    }

    static Report analyze(RepositoryIndex.Snapshot snapshot) {
        var declarations = new ArrayList<Decl>();
        int skippedFiles = 0,
            unresolved = 0;
        boolean truncated = false;
        for (var file : snapshot.files()) {
            if (!file.language().equals("java")) continue;
            if (file.content().startsWith("[File withheld")) {
                skippedFiles++;
                continue;
            }
            var parsed = new JavaParser(
                new ParserConfiguration().setLanguageLevel(
                    ParserConfiguration.LanguageLevel.JAVA_21
                )
            ).parse(file.content());
            if (!parsed.isSuccessful()) {
                skippedFiles++;
                continue;
            }
            var unit = parsed.getResult().orElseThrow();
            for (var raw : unit.findAll(TypeDeclaration.class)) {
                TypeDeclaration<?> type = raw;
                String name = qualified(type, unit);
                if (name == null) continue;
                if (declarations.size() == MAX_TYPES) {
                    truncated = true;
                    break;
                }
                declarations.add(new Decl(name, file.path(), type, unit));
            }
            if (truncated) break;
        }
        var grouped = new TreeMap<String, List<Decl>>();
        declarations.forEach(d ->
            grouped.computeIfAbsent(d.name(), k -> new ArrayList<>()).add(d)
        );
        var unique = new TreeMap<String, Decl>();
        grouped.forEach((name, matches) -> {
            if (matches.size() == 1) unique.put(name, matches.getFirst());
        });
        var relationships = new ArrayList<Relationship>();
        outer: for (var owner : unique.values()) {
            record Field(Type type, int line) {}
            var fields = new ArrayList<Field>();
            owner
                .node()
                .getFields()
                .forEach(f ->
                    f
                        .getVariables()
                        .forEach(v ->
                            fields.add(new Field(v.getType(), line(v)))
                        )
                );
            if (owner.node() instanceof RecordDeclaration record) record
                .getParameters()
                .forEach(p -> fields.add(new Field(p.getType(), line(p))));
            for (var field : fields) {
                Type type = field.type();
                while (type.isArrayType())
                    type = type.asArrayType().getComponentType();
                if (!type.isClassOrInterfaceType()) continue;
                String reference = type
                    .asClassOrInterfaceType()
                    .getNameWithScope();
                var target = resolve(reference, owner, unique);
                if (target == null) {
                    unresolved++;
                    continue;
                }
                if (owner.id().equals(target.id())) continue; // Recursive data structures are not multi-type coupling cycles.
                if (relationships.size() == MAX_EDGES) {
                    truncated = true;
                    break outer;
                }
                relationships.add(
                    new Relationship(
                        owner.id(),
                        target.id(),
                        owner.path(),
                        field.line()
                    )
                );
            }
        }
        relationships.sort(
            Comparator.comparing(Relationship::from)
                .thenComparing(Relationship::to)
                .thenComparingInt(Relationship::line)
        );
        var outgoing = new TreeMap<String, Set<String>>();
        var incoming = new TreeMap<String, Set<String>>();
        unique.values().forEach(d -> {
            outgoing.put(d.id(), new TreeSet<>());
            incoming.put(d.id(), new TreeSet<>());
        });
        relationships.forEach(e -> {
            outgoing.get(e.from()).add(e.to());
            incoming.get(e.to()).add(e.from());
        });
        var allCycles = components(outgoing, incoming)
            .stream()
            .filter(c -> c.size() > 1)
            .sorted(Comparator.comparing(c -> c.getFirst()))
            .toList();
        var cycles = new ArrayList<Cycle>();
        for (var members : allCycles.stream().limit(20).toList()) {
            var set = Set.copyOf(members);
            var evidence = relationships
                .stream()
                .filter(e -> set.contains(e.from()) && set.contains(e.to()))
                .toList();
            cycles.add(
                new Cycle(
                    members,
                    evidence.stream().limit(40).toList(),
                    evidence.size() > 40
                )
            );
        }
        var types = unique
            .values()
            .stream()
            .map(d ->
                new Declaration(
                    d.id(),
                    d.name(),
                    d.path(),
                    line(d.node()),
                    incoming.get(d.id()).size(),
                    outgoing.get(d.id()).size()
                )
            )
            .toList();
        var candidates = types
            .stream()
            .filter(d -> d.fanIn() >= 5 || d.fanOut() >= 5)
            .sorted(
                Comparator.comparingInt(
                    (Declaration d) -> d.fanIn() + d.fanOut()
                )
                    .reversed()
                    .thenComparing(Declaration::id)
            )
            .toList();
        var hotspots = candidates
            .stream()
            .limit(20)
            .map(d -> {
                var evidence = relationships
                    .stream()
                    .filter(
                        e -> e.from().equals(d.id()) || e.to().equals(d.id())
                    )
                    .toList();
                return new Hotspot(
                    d.id(),
                    d.fanIn(),
                    d.fanOut(),
                    evidence.stream().limit(20).toList(),
                    evidence.size() > 20
                );
            })
            .toList();
        return new Report(
            snapshot.id(),
            types,
            List.copyOf(relationships),
            List.copyOf(cycles),
            hotspots,
            unresolved,
            skippedFiles,
            truncated || allCycles.size() > 20 || candidates.size() > 20,
            List.of(
                "Java syntax only: direct field types, array element types and record components. Generic arguments, method-only dependencies, inheritance, wildcard imports, static imports and external libraries are not resolved.",
                "Resolution uses enclosing declarations, explicit imports and package names; duplicate qualified declarations and type-parameter/inheritance ambiguity are skipped. Parsing does not prove that the source compiles.",
                "Cycles are strongly connected groups of at least two resolved types. Self references are excluded. No cycles in this subset does not establish an acyclic repository or runtime dependency graph.",
                "Coupling review threshold: at least five distinct incoming or outgoing types. This is a review prompt, not a defect, quality score or runtime-call assertion.",
                "At most 1000 types, 3000 declaration references, 20 cycle groups and 20 coupling candidates. Group evidence is capped at 40/20 references; the exported relationship list retains the bounded graph.",
                "Only the approved redacted snapshot is read. No repository code, build, dependency installation, model call or filesystem refresh occurs."
            )
        );
    }

    private static Decl resolve(
        String reference,
        Decl owner,
        Map<String, Decl> types
    ) {
        var parameters = new HashSet<String>();
        boolean inherited = false;
        String first = reference.contains(".")
            ? reference.substring(0, reference.indexOf('.'))
            : reference;
        for (var node = owner.node(); node != null; ) {
            if (node instanceof ClassOrInterfaceDeclaration c) {
                c.getTypeParameters().forEach(p ->
                    parameters.add(p.getNameAsString())
                );
                inherited |=
                    !c.getExtendedTypes().isEmpty() ||
                    !c.getImplementedTypes().isEmpty();
            }
            if (node instanceof RecordDeclaration r) {
                r.getTypeParameters().forEach(p ->
                    parameters.add(p.getNameAsString())
                );
                inherited |= !r.getImplementedTypes().isEmpty();
            }
            if (node instanceof EnumDeclaration e) inherited |= !e
                .getImplementedTypes()
                .isEmpty();
            if (parameters.contains(first)) return null;
            String name = qualified(node, owner.unit());
            if (
                name != null && types.containsKey(name + "." + reference)
            ) return types.get(name + "." + reference);
            // An inherited member type can shadow a declaration in an enclosing scope.
            if (inherited && !types.containsKey(reference)) return null;
            node = node
                .getParentNode()
                .filter(p -> p instanceof TypeDeclaration<?>)
                .map(p -> (TypeDeclaration<?>) p)
                .orElse(null);
        }
        if (
            reference.contains(".") && types.containsKey(reference)
        ) return types.get(reference);
        // Inherited member types may shadow imports or package types; do not guess without a type solver.
        if (inherited) return null;
        if (
            owner
                .unit()
                .getImports()
                .stream()
                .anyMatch(
                    i ->
                        i.isStatic() &&
                        !i.isAsterisk() &&
                        i.getName().getIdentifier().equals(first)
                )
        ) return null;
        var imports = owner
            .unit()
            .getImports()
            .stream()
            .filter(i -> !i.isStatic() && !i.isAsterisk())
            .filter(i -> i.getName().getIdentifier().equals(first))
            .map(i -> i.getNameAsString() + reference.substring(first.length()))
            .distinct()
            .toList();
        if (!imports.isEmpty()) return imports.size() == 1
            ? types.get(imports.getFirst())
            : null;
        return types.get(
            (owner.pkg().isEmpty() ? "" : owner.pkg() + ".") + reference
        );
    }

    private static String qualified(
        TypeDeclaration<?> declaration,
        CompilationUnit unit
    ) {
        var parts = new ArrayDeque<String>();
        com.github.javaparser.ast.Node cursor = declaration;
        while (!(cursor instanceof CompilationUnit)) {
            if (!(cursor instanceof TypeDeclaration<?> type)) return null; // Local and anonymous types have unsupported scopes.
            parts.addFirst(type.getNameAsString());
            cursor = cursor.getParentNode().orElse(unit);
        }
        return (
            unit
                .getPackageDeclaration()
                .map(p -> p.getNameAsString() + ".")
                .orElse("") + String.join(".", parts)
        );
    }

    private static int line(com.github.javaparser.ast.Node node) {
        return node
            .getBegin()
            .map(p -> p.line)
            .orElse(1);
    }

    /** Iterative Kosaraju avoids recursive traversal on long dependency chains. */
    private static List<List<String>> components(
        Map<String, Set<String>> graph,
        Map<String, Set<String>> reverse
    ) {
        record Frame(String node, Iterator<String> children) {}
        var visited = new HashSet<String>();
        var order = new ArrayList<String>();
        for (String node : graph.keySet()) {
            if (!visited.add(node)) continue;
            var stack = new ArrayDeque<Frame>();
            stack.push(new Frame(node, graph.get(node).iterator()));
            while (!stack.isEmpty()) {
                var frame = stack.peek();
                if (frame.children().hasNext()) {
                    String child = frame.children().next();
                    if (visited.add(child)) stack.push(
                        new Frame(child, graph.get(child).iterator())
                    );
                } else {
                    order.add(stack.pop().node());
                }
            }
        }
        visited.clear();
        var result = new ArrayList<List<String>>();
        for (String node : order.reversed()) {
            if (!visited.add(node)) continue;
            var group = new TreeSet<String>();
            var stack = new ArrayDeque<String>();
            stack.push(node);
            while (!stack.isEmpty()) {
                String current = stack.pop();
                group.add(current);
                for (String next : reverse.get(current))
                    if (visited.add(next)) stack.push(next);
            }
            result.add(List.copyOf(group));
        }
        return result;
    }
}
