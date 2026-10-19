package dev.atlas.intelligence;

import com.github.javaparser.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import dev.atlas.platform.ApiException;
import dev.atlas.workspace.Documents;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RepositoryIndex {

    public record Source(
        String path,
        String language,
        int lines,
        String content,
        List<String> warnings
    ) {}

    public record Symbol(
        String path,
        int line,
        int endLine,
        String name,
        String kind,
        String detail
    ) {}

    public record Edge(
        String from,
        String to,
        String evidence,
        String path,
        int line
    ) {}

    public record Call(
        String caller,
        String callee,
        String path,
        int line,
        String targetPath,
        int targetLine,
        String evidence
    ) {}

    public record Snapshot(
        UUID id,
        List<Source> files,
        List<Symbol> symbols,
        List<Edge> edges,
        List<Call> calls,
        List<String> findings,
        Map<String, Long> languages,
        long bytes
    ) {}

    private static final Set<String> EXCLUDED = Set.of(
        ".git",
        ".runtime",
        ".idea",
        ".vscode",
        "node_modules",
        "target",
        "dist",
        "build",
        ".next",
        "coverage",
        "test-results",
        "playwright-report",
        "attachments"
    );
    private static final Set<String> EXTENSIONS = Set.of(
        "java",
        "ts",
        "tsx",
        "js",
        "jsx",
        "md",
        "sql",
        "xml",
        "yaml",
        "yml",
        "json",
        "css",
        "properties"
    );
    private final Path configuredRoot;
    private final Documents documents;
    private volatile Snapshot snapshot;
    private volatile Path approvedRoot;

    public RepositoryIndex(
        @Value("${atlas.repository-root:..}") String root,
        Documents documents
    ) {
        configuredRoot = Path.of(root).toAbsolutePath().normalize();
        this.documents = documents;
    }

    public Map<String, Object> configuration() {
        return Map.of(
            "rootLabel",
            "Configured local workspace",
            "maxFiles",
            500,
            "maxBytes",
            4_000_000,
            "maxFileBytes",
            160_000,
            "indexed",
            snapshot != null
        );
    }

    public synchronized Snapshot index(String relative, boolean approved) {
        if (!approved) reject(
            "Explicit read-only import approval is required."
        );
        if (
            relative == null ||
            relative.length() > 200 ||
            relative.contains("\\") ||
            relative.contains(":") ||
            relative.startsWith("/") ||
            Arrays.asList(relative.split("/")).contains("..")
        ) reject("Use a relative path inside the configured workspace.");
        try {
            Path base = configuredRoot.toRealPath();
            Path root = base.resolve(relative).normalize();
            verifyPath(base, root);
            if (
                !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isDirectory(
                    root.resolve(".git"),
                    LinkOption.NOFOLLOW_LINKS
                )
            ) reject(
                "Select a local Git repository with an ordinary .git directory."
            );
            var files = new ArrayList<Source>();
            var symbols = new ArrayList<Symbol>();
            var edges = new ArrayList<Edge>();
            var findings = new ArrayList<String>();
            long[] bytes = { 0 };
            int[] visited = { 0 };
            Files.walkFileTree(
                root,
                EnumSet.noneOf(FileVisitOption.class),
                18,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                        Path dir,
                        BasicFileAttributes attrs
                    ) {
                        if (++visited[0] > 5000) reject(
                            "Repository traversal exceeds 5000 entries."
                        );
                        if (
                            !dir.equals(root) &&
                            (dir.getFileName().toString().startsWith(".") ||
                                EXCLUDED.contains(dir.getFileName().toString()))
                        ) return FileVisitResult.SKIP_SUBTREE;
                        if (
                            Files.isSymbolicLink(dir) ||
                            !dir.toAbsolutePath().normalize().startsWith(root)
                        ) reject("Symbolic links are not followed.");
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(
                        Path path,
                        BasicFileAttributes attrs
                    ) throws IOException {
                        if (++visited[0] > 5000) reject(
                            "Repository traversal exceeds 5000 entries."
                        );
                        String name = path.getFileName().toString();
                        String ext = name.contains(".")
                            ? name.substring(name.lastIndexOf('.') + 1)
                            : "";
                        if (
                            !attrs.isRegularFile() ||
                            Files.isSymbolicLink(path) ||
                            name.startsWith(".") ||
                            name
                                .toLowerCase()
                                .matches(
                                    ".*(?:secret|credential|password|private|lock).*"
                                )
                        ) return FileVisitResult.CONTINUE;
                        if (
                            !EXTENSIONS.contains(ext)
                        ) return FileVisitResult.CONTINUE;
                        if (attrs.size() > 160000) {
                            findings.add(
                                root.relativize(path) +
                                    ": skipped file above 160 KiB limit"
                            );
                            return FileVisitResult.CONTINUE;
                        }
                        if (
                            files.size() >= 500 ||
                            bytes[0] + attrs.size() > 4_000_000
                        ) reject(
                            "Index limit is 500 files / 4 MB. Choose a smaller repository."
                        );
                        verifyPath(root, path);
                        ByteBuffer buffer = ByteBuffer.allocate(160001);
                        try (
                            var channel = Files.newByteChannel(
                                path,
                                Set.of(
                                    StandardOpenOption.READ,
                                    LinkOption.NOFOLLOW_LINKS
                                )
                            )
                        ) {
                            while (
                                buffer.hasRemaining() &&
                                channel.read(buffer) > 0
                            ) {}
                        }
                        if (buffer.position() > 160000) reject(
                            "File changed beyond the size limit during import."
                        );
                        verifyPath(root, path);
                        buffer.flip();
                        String content;
                        try {
                            content = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .decode(buffer)
                                .toString();
                        } catch (CharacterCodingException ex) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (
                            content.indexOf('\0') >= 0
                        ) return FileVisitResult.CONTINUE;
                        String safe = Redaction.mask(content);
                        String relativePath = root
                            .relativize(path)
                            .toString()
                            .replace('\\', '/');
                        var warnings = new ArrayList<String>();
                        if (!safe.equals(content)) warnings.add(
                            "Potential credentials masked; heuristic detection cannot guarantee all secrets are found."
                        );
                        if (Redaction.injection(content)) warnings.add(
                            "Instruction-like content is untrusted source data, never an instruction."
                        );
                        files.add(
                            new Source(
                                relativePath,
                                ext,
                                safe.split("\\R", -1).length,
                                safe,
                                warnings
                            )
                        );
                        bytes[0] += buffer.limit();
                        if (ext.equals("java")) parse(
                            relativePath,
                            content,
                            symbols,
                            edges,
                            findings
                        );
                        if (safe.split("\\R", -1).length > 500) findings.add(
                            relativePath +
                                ": large file; consider responsibility boundaries (heuristic)."
                        );
                        return FileVisitResult.CONTINUE;
                    }
                }
            );
            files.sort(Comparator.comparing(Source::path));
            Map<String, Long> languages = new TreeMap<>();
            files.forEach(f -> languages.merge(f.language(), 1L, Long::sum));
            Set<String> names = new HashSet<>();
            symbols
                .stream()
                .filter(s -> s.kind().equals("type"))
                .forEach(s -> names.add(s.name()));
            edges.removeIf(e -> !names.contains(e.to()));
            var next = new Snapshot(
                UUID.randomUUID(),
                List.copyOf(files),
                List.copyOf(symbols),
                List.copyOf(edges),
                calls(files),
                List.copyOf(findings),
                languages,
                bytes[0]
            );
            documents.audit("REPOSITORY_APPROVED", next.id().toString());
            approvedRoot = root;
            snapshot = next;
            return next;
        } catch (IOException e) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "IMPORT_FAILED",
                "Cannot read the selected workspace safely."
            );
        }
    }

    private void verifyPath(Path base, Path target) throws IOException {
        if (!target.normalize().startsWith(base)) reject(
            "Path escapes the approved root."
        );
        Path cursor = base;
        for (Path part : base.relativize(target)) {
            cursor = cursor.resolve(part);
            if (
                Files.isSymbolicLink(cursor) ||
                (!Files.isRegularFile(cursor, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
            ) reject("Links and special files are not allowed.");
        }
        if (!target.toRealPath().startsWith(base.toRealPath())) reject(
            "Resolved path escapes the approved root."
        );
    }

    private void parse(
        String path,
        String content,
        List<Symbol> symbols,
        List<Edge> edges,
        List<String> findings
    ) {
        var parsed = new JavaParser(
            new ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.JAVA_21
            )
        ).parse(content);
        if (!parsed.isSuccessful()) {
            findings.add(
                path +
                    ": Java parser could not establish a complete AST; no inferred relationships."
            );
            return;
        }
        var unit = parsed.getResult().orElseThrow();
        unit.findAll(TypeDeclaration.class).forEach(raw -> {
            TypeDeclaration<?> type = raw;
            symbols.add(
                new Symbol(
                    path,
                    line(type),
                    end(type),
                    type.getNameAsString(),
                    "type",
                    type
                        .getAnnotations()
                        .stream()
                        .map(a -> a.getNameAsString())
                        .sorted()
                        .toList()
                        .toString()
                )
            );
            type.getFields().forEach(field ->
                field
                    .getVariables()
                    .forEach(v ->
                        edges.add(
                            new Edge(
                                type.getNameAsString(),
                                v.getType().asString(),
                                "Declared field " +
                                    v.getNameAsString() +
                                    "; does not prove a runtime call",
                                path,
                                line(v)
                            )
                        )
                    )
            );
        });
        unit.findAll(MethodDeclaration.class).forEach(method -> {
            String annotations = Redaction.mask(
                method.getAnnotations().toString()
            );
            symbols.add(
                new Symbol(
                    path,
                    line(method),
                    end(method),
                    method.getNameAsString(),
                    "method",
                    annotations
                )
            );
            if (
                method
                    .getAnnotations()
                    .stream()
                    .anyMatch(a ->
                        Set.of(
                            "GetMapping",
                            "PostMapping",
                            "PutMapping",
                            "DeleteMapping",
                            "PatchMapping",
                            "RequestMapping"
                        ).contains(a.getNameAsString())
                    )
            ) symbols.add(
                new Symbol(
                    path,
                    line(method),
                    end(method),
                    method.getNameAsString(),
                    "endpoint",
                    annotations + " · class mapping must also be inspected"
                )
            );
            if (
                method
                    .getAnnotations()
                    .stream()
                    .anyMatch(a -> a.getNameAsString().equals("Transactional"))
            ) symbols.add(
                new Symbol(
                    path,
                    line(method),
                    end(method),
                    method.getNameAsString(),
                    "transaction",
                    annotations +
                        " · proxy-managed boundary; commit occurs after successful proxy return"
                )
            );
            if (end(method) - line(method) > 80) findings.add(
                path +
                    ":" +
                    line(method) +
                    " long method " +
                    method.getNameAsString() +
                    " (>80 lines, heuristic)"
            );
        });
        unit.getImports().forEach(i ->
            symbols.add(
                new Symbol(
                    path,
                    line(i),
                    end(i),
                    i.getNameAsString(),
                    "import",
                    "Declared Java import; usage and runtime dependency not established"
                )
            )
        );
    }

    private int line(com.github.javaparser.ast.Node n) {
        return n
            .getBegin()
            .map(p -> p.line)
            .orElse(1);
    }

    private List<Call> calls(List<Source> files) {
        record Type(String path, ClassOrInterfaceDeclaration declaration) {}
        var types = new TreeMap<String, List<Type>>();
        for (var file : files)
            if (file.language().equals("java")) {
                var parsed = new JavaParser(
                    new ParserConfiguration().setLanguageLevel(
                        ParserConfiguration.LanguageLevel.JAVA_21
                    )
                ).parse(file.content());
                if (parsed.isSuccessful()) parsed
                    .getResult()
                    .orElseThrow()
                    .findAll(ClassOrInterfaceDeclaration.class)
                    .forEach(t ->
                        types
                            .computeIfAbsent(t.getNameAsString(), k ->
                                new ArrayList<>()
                            )
                            .add(new Type(file.path(), t))
                    );
            }
        var result = new ArrayList<Call>();
        for (var group : types.values())
            if (group.size() == 1) {
                var owner = group.getFirst();
                var fields = new HashMap<String, String>();
                owner
                    .declaration()
                    .getFields()
                    .forEach(f ->
                        f
                            .getVariables()
                            .forEach(v ->
                                fields.put(
                                    v.getNameAsString(),
                                    v.getType().asString()
                                )
                            )
                    );
                for (var method : owner.declaration().getMethods())
                    for (var call : method.findAll(MethodCallExpr.class)) {
                        String targetType = owner
                            .declaration()
                            .getNameAsString();
                        if (call.getScope().isPresent()) {
                            var scope = call.getScope().get();
                            if (scope.isNameExpr()) {
                                String receiver = scope
                                    .asNameExpr()
                                    .getNameAsString();
                                if (
                                    method
                                        .getParameters()
                                        .stream()
                                        .anyMatch(p ->
                                            p.getNameAsString().equals(receiver)
                                        ) ||
                                    method
                                        .findAll(VariableDeclarator.class)
                                        .stream()
                                        .anyMatch(v ->
                                            v.getNameAsString().equals(receiver)
                                        )
                                ) continue;
                                targetType = fields.get(receiver);
                            } else if (!scope.isThisExpr()) continue;
                        }
                        if (targetType == null) continue;
                        var targetGroup = types.get(targetType);
                        if (
                            targetGroup == null || targetGroup.size() != 1
                        ) continue;
                        var target = targetGroup.getFirst();
                        var candidates = target
                            .declaration()
                            .getMethodsByName(call.getNameAsString())
                            .stream()
                            .filter(
                                m ->
                                    m.getParameters().size() ==
                                    call.getArguments().size()
                            )
                            .toList();
                        if (candidates.size() != 1) continue;
                        var declaration = candidates.getFirst();
                        result.add(
                            new Call(
                                owner.declaration().getNameAsString() +
                                    "." +
                                    method.getNameAsString(),
                                target.declaration().getNameAsString() +
                                    "." +
                                    declaration.getNameAsString(),
                                owner.path(),
                                line(call),
                                target.path(),
                                line(declaration),
                                "Potential call resolved from declared field/owner, method name and arity. Branches, dynamic dispatch, proxies and runtime execution are not established."
                            )
                        );
                        if (result.size() >= 1000) return List.copyOf(result);
                    }
            }
        return List.copyOf(result);
    }

    private int end(com.github.javaparser.ast.Node n) {
        return n
            .getEnd()
            .map(p -> p.line)
            .orElse(1);
    }

    public Snapshot current() {
        Snapshot s = snapshot;
        if (s == null) throw new ApiException(
            HttpStatus.CONFLICT,
            "NO_INDEX",
            "Approve and import a repository first."
        );
        return s;
    }

    public Source file(String path) {
        return current()
            .files()
            .stream()
            .filter(f -> f.path().equals(path))
            .findFirst()
            .orElseThrow(() ->
                new ApiException(
                    HttpStatus.NOT_FOUND,
                    "FILE_NOT_INDEXED",
                    "File is outside the indexed snapshot."
                )
            );
    }

    public synchronized void clear() {
        snapshot = null;
        approvedRoot = null;
        documents.audit("REPOSITORY_DELETED", "local-index");
    }

    public Map<String, String> git(String path) {
        file(path);
        Path root = approvedRoot;
        if (root == null) reject("No approved repository.");
        return Map.of(
            "history",
            gitCommand(
                root,
                List.of(
                    "log",
                    "-8",
                    "--no-show-signature",
                    "--format=%h %ad %s",
                    "--date=short",
                    "--",
                    path
                )
            ),
            "changes",
            gitCommand(
                root,
                List.of(
                    "diff",
                    "--no-ext-diff",
                    "--no-textconv",
                    "--unified=2",
                    "--",
                    path
                )
            )
        );
    }

    private String gitCommand(Path root, List<String> args) {
        // Fixed Git builtin only, no shell, no hooks, no pager, no external diff or text conversion.
        var command = new ArrayList<>(
            List.of(
                "git",
                "--no-pager",
                "-c",
                "core.fsmonitor=false",
                "-c",
                "core.hooksPath=",
                "-c",
                "log.showSignature=false",
                "-c",
                "safe.directory="+root.toString(),
                "-C",
                root.toString()
            )
        );
        command.addAll(args);
        try {
            var builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = builder.start();
            try (
                var executor =
                    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
            ) {
                var output = executor.submit(() ->
                    process.getInputStream().readNBytes(32001)
                );
                try {
                    byte[] data = output.get(3, TimeUnit.SECONDS);
                    if (data.length > 32000) {
                        process.destroyForcibly();
                        return "Git output exceeds 32 KiB; narrow the selection.";
                    }
                    if (
                        !process.waitFor(1, TimeUnit.SECONDS)
                    ) process.destroyForcibly();
                    return process.exitValue() == 0
                        ? Redaction.mask(
                              new String(data, StandardCharsets.UTF_8)
                          )
                        : "Git history unavailable (repository may have no commits).";
                } catch (Exception e) {
                    process.destroyForcibly();
                    return "Git inspection timed out.";
                }
            }
        } catch (IOException e) {
            return "Git is unavailable in this runtime.";
        }
    }

    private static void reject(String message) {
        throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "UNSAFE_IMPORT",
            message
        );
    }
}
