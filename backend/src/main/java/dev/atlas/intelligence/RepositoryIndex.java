package dev.atlas.intelligence;

import com.github.javaparser.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import dev.atlas.platform.ApiException;
import dev.atlas.platform.RequestContext;
import dev.atlas.workspace.Documents;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RepositoryIndex {

    public record Origin(
        String kind,
        String repositoryUrl,
        String commit,
        String directory
    ) {
        static Origin local() {
            return new Origin("LOCAL", "", "", "");
        }
    }

    public record RemoteFile(String path, String content) {}

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
        long bytes,
        Origin origin
    ) {
        public Snapshot {
            if (origin == null) origin = Origin.local();
        }

        public Snapshot(
            UUID id,
            List<Source> files,
            List<Symbol> symbols,
            List<Edge> edges,
            List<Call> calls,
            List<String> findings,
            Map<String, Long> languages,
            long bytes
        ) {
            this(
                id,
                files,
                symbols,
                edges,
                calls,
                findings,
                languages,
                bytes,
                Origin.local()
            );
        }
    }

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
        "properties",
        "py",
        "pyi",
        "go",
        "rs",
        "c",
        "h",
        "cpp",
        "hpp",
        "cs",
        "kt",
        "kts",
        "rb",
        "php",
        "swift",
        "html",
        "scss",
        "vue",
        "svelte",
        "toml",
        "txt",
        "mdx",
        "sh"
    );
    private final Path configuredRoot;
    private final Documents documents;

    private record Published(
        Snapshot snapshot,
        Path approvedRoot,
        long revision
    ) {}

    private record Draft(Snapshot snapshot, Path root) {}

    private final AtomicReference<Published> published = new AtomicReference<>(
        new Published(null, null, 0)
    );
    private final AtomicBoolean localBusy = new AtomicBoolean();
    private final AtomicReference<FutureTask<Draft>> localWork =
        new AtomicReference<>();
    private final LongSupplier nanoTime;
    private final long localBudgetNanos;
    private final Consumer<String> localStage;

    static boolean supportedPath(String path) {
        if (path == null || path.isEmpty()) return false;
        var parts = path.split("/", -1);
        for (String part : parts) {
            if (
                part.isEmpty() ||
                part.startsWith(".") ||
                EXCLUDED.contains(part)
            ) return false;
        }
        String name = parts[parts.length - 1];
        if (
            name
                .toLowerCase(Locale.ROOT)
                .matches(".*(?:secret|credential|password|private|lock).*")
        ) return false;
        int dot = name.lastIndexOf('.');
        return (
            name.equals("go.mod") ||
            (dot >= 0 &&
                EXTENSIONS.contains(
                    name.substring(dot + 1).toLowerCase(Locale.ROOT)
                ))
        );
    }

    long importRevision() {
        return published.get().revision();
    }

    /** Publishes bounded archive text atomically; archive paths never address the filesystem. */
    Snapshot indexRemote(
        long expectedRevision,
        List<RemoteFile> rawFiles,
        Origin origin
    ) {
        return indexRemote(expectedRevision, rawFiles, origin, List.of());
    }

    Snapshot indexRemote(
        long expectedRevision,
        List<RemoteFile> rawFiles,
        Origin origin,
        List<String> importFindings
    ) {
        Published expected = published.get();
        if (expected.revision() != expectedRevision) throw superseded();
        if (rawFiles.isEmpty() || rawFiles.size() > 500) reject(
            "Remote snapshot must contain 1 to 500 supported files."
        );
        var files = new ArrayList<Source>();
        var symbols = new ArrayList<Symbol>();
        var edges = new ArrayList<Edge>();
        var findings = new ArrayList<String>(importFindings);
        var seen = new HashSet<String>();
        long bytes = 0;
        for (var raw : rawFiles) {
            String path = raw.path();
            if (
                !supportedPath(path) ||
                path.contains("\\") ||
                path.contains(":") ||
                path.chars().anyMatch(c -> c < 32) ||
                !seen.add(path)
            ) reject("Unsafe or duplicate remote snapshot path.");
            int size = raw.content().getBytes(StandardCharsets.UTF_8).length;
            if (
                size > 160000 ||
                raw.content().indexOf('\0') >= 0 ||
                (bytes += size) > 4_000_000
            ) reject("Remote snapshot exceeds source text bounds.");
            String ext = path
                .substring(path.lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
            files.add(source(path, ext, raw.content()));
            if (ext.equals("java")) parse(
                path,
                raw.content(),
                symbols,
                edges,
                findings
            );
            if (raw.content().split("\\R", -1).length > 500) findings.add(
                path +
                    ": large file; consider responsibility boundaries (heuristic)."
            );
        }
        files.sort(Comparator.comparing(Source::path));
        var languages = new TreeMap<String, Long>();
        files.forEach(f -> languages.merge(f.language(), 1L, Long::sum));
        var names = new HashSet<String>();
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
            bytes,
            origin
        );
        if (published.get() != expected) throw superseded();
        // Approval is audited before publication; supersession can still occur
        // during this database call, so the final CAS remains essential.
        documents.audit("REPOSITORY_GITHUB_APPROVED", next.id().toString());
        if (
            !published.compareAndSet(
                expected,
                new Published(next, null, expectedRevision + 1)
            )
        ) throw superseded();
        return next;
    }

    private static Source source(String path, String ext, String content) {
        String safe = Redaction.mask(content);
        var warnings = new ArrayList<String>();
        if (!safe.equals(content)) warnings.add(
            "Potential credentials masked; heuristic detection cannot guarantee all secrets are found."
        );
        if (Redaction.injection(content)) warnings.add(
            "Instruction-like content is untrusted source data, never an instruction."
        );
        return new Source(
            path,
            ext,
            safe.split("\\R", -1).length,
            safe,
            List.copyOf(warnings)
        );
    }

    @Autowired
    public RepositoryIndex(
        @Value("${atlas.repository-root:..}") String root,
        Documents documents
    ) {
        this(
            root,
            documents,
            System::nanoTime,
            TimeUnit.SECONDS.toNanos(12),
            stage -> {}
        );
    }

    RepositoryIndex(
        String root,
        Documents documents,
        LongSupplier nanoTime,
        long localBudgetNanos,
        Consumer<String> localStage
    ) {
        configuredRoot = Path.of(root).toAbsolutePath().normalize();
        this.documents = documents;
        this.nanoTime = nanoTime;
        this.localBudgetNanos = localBudgetNanos;
        this.localStage = localStage;
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
            "localImportBudgetMs",
            TimeUnit.NANOSECONDS.toMillis(localBudgetNanos),
            "indexed",
            published.get().snapshot() != null
        );
    }

    public Snapshot index(String relative, boolean approved) {
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
        // Resolve fallible request context before admission so an invalid MDC
        // value cannot strand the only worker slot.
        String correlation = RequestContext.correlationId().toString();
        long started = nanoTime.getAsLong();
        if (!localBusy.compareAndSet(false, true)) throw new ApiException(
            HttpStatus.TOO_MANY_REQUESTS,
            "LOCAL_IMPORT_BUSY",
            "A local import worker is still active. Wait for it to finish before importing again."
        );
        Published expected = published.get();
        var exited = new CountDownLatch(1);
        var work = new FutureTask<Draft>(() -> {
            MDC.put("correlationId", correlation);
            try {
                checkpoint("scan", started);
                if (published.get() != expected) throw superseded();
                Draft draft = localDraft(relative, started);
                checkpoint("audit", started);
                if (published.get() != expected) throw superseded();
                // This records approval of an attempted import, not publication.
                documents.audit(
                    "REPOSITORY_APPROVED",
                    draft.snapshot().id().toString()
                );
                checkpoint("complete", started);
                return draft;
            } finally {
                MDC.clear();
            }
        });
        localWork.set(work);
        boolean launched = false;
        try {
            Thread.ofPlatform()
                .daemon(true)
                .name("atlas-local-import")
                .start(() -> {
                    try {
                        work.run();
                    } finally {
                        localWork.compareAndSet(work, null);
                        localBusy.set(false);
                        exited.countDown();
                        localStage.accept("worker-exit");
                    }
                });
            launched = true;
            long remaining =
                localBudgetNanos - (nanoTime.getAsLong() - started);
            if (remaining <= 0) throw localTimeout();
            Draft draft = work.get(remaining, TimeUnit.NANOSECONDS);
            remaining = localBudgetNanos - (nanoTime.getAsLong() - started);
            if (
                remaining <= 0 || !exited.await(remaining, TimeUnit.NANOSECONDS)
            ) throw localTimeout();
            checkpoint("publish", started);
            if (
                !published.compareAndSet(
                    expected,
                    new Published(
                        draft.snapshot(),
                        draft.root(),
                        expected.revision() + 1
                    )
                )
            ) throw superseded();
            return draft.snapshot();
        } catch (TimeoutException e) {
            throw localTimeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "LOCAL_IMPORT_INTERRUPTED",
                "The local import request was interrupted; no snapshot was published."
            );
        } catch (CancellationException e) {
            if (published.get() != expected) throw superseded();
            throw localTimeout();
        } catch (ExecutionException e) {
            long remaining =
                localBudgetNanos - (nanoTime.getAsLong() - started);
            if (remaining > 0) {
                try {
                    exited.await(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            if (e.getCause() instanceof Error failure) throw failure;
            throw new IllegalStateException(
                "Local import worker failed.",
                e.getCause()
            );
        } finally {
            // Future cancellation does not imply worker termination. Admission is
            // released only by the actual worker's finally, including ignored interrupts.
            work.cancel(true);
            if (!launched) {
                localWork.compareAndSet(work, null);
                localBusy.set(false);
            }
        }
    }

    private void checkpoint(String stage, long started) {
        localStage.accept(stage);
        if (
            Thread.currentThread().isInterrupted() ||
            nanoTime.getAsLong() - started >= localBudgetNanos
        ) throw localTimeout();
    }

    private ApiException localTimeout() {
        return new ApiException(
            // The request body was received completely. HTTP 408 describes an
            // incomplete request and clients can automatically resend it.
            HttpStatus.SERVICE_UNAVAILABLE,
            "LOCAL_IMPORT_TIMEOUT",
            "Local import exceeded its " +
                TimeUnit.NANOSECONDS.toMillis(localBudgetNanos) +
                " ms processing budget. No snapshot was published; choose a smaller repository."
        );
    }

    private static ApiException superseded() {
        return new ApiException(
            HttpStatus.CONFLICT,
            "IMPORT_SUPERSEDED",
            "The import was superseded by deletion or another repository import."
        );
    }

    private Draft localDraft(String relative, long started) {
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
                        checkpoint("directory", started);
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
                        checkpoint("file", started);
                        if (++visited[0] > 5000) reject(
                            "Repository traversal exceeds 5000 entries."
                        );
                        String name = path.getFileName().toString();
                        String ext = name.contains(".")
                            ? name
                                  .substring(name.lastIndexOf('.') + 1)
                                  .toLowerCase(Locale.ROOT)
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
                            !EXTENSIONS.contains(ext) && !name.equals("go.mod")
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
                            ) {
                                checkpoint("read", started);
                            }
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
                        checkpoint("parsed", started);
                        if (safe.split("\\R", -1).length > 500) findings.add(
                            relativePath +
                                ": large file; consider responsibility boundaries (heuristic)."
                        );
                        return FileVisitResult.CONTINUE;
                    }
                }
            );
            checkpoint("calls", started);
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
            checkpoint("draft", started);
            return new Draft(next, root);
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
        return requireSnapshot(published.get());
    }

    private static Snapshot requireSnapshot(Published state) {
        Snapshot s = state.snapshot();
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

    public void clear() {
        // Capture first: a job admitted after deletion must not be cancelled.
        var work = localWork.get();
        published.updateAndGet(state ->
            new Published(null, null, state.revision() + 1)
        );
        if (work != null) work.cancel(true);
        documents.audit("REPOSITORY_DELETED", "local-index");
    }

    public Map<String, String> git(String path) {
        Published state = published.get();
        var current = requireSnapshot(state);
        if (
            current
                .files()
                .stream()
                .noneMatch(f -> f.path().equals(path))
        ) throw new ApiException(
            HttpStatus.NOT_FOUND,
            "FILE_NOT_INDEXED",
            "File is outside the indexed snapshot."
        );
        if (current.origin().kind().equals("GITHUB")) return Map.of(
            "history",
            "GitHub source snapshot at commit " +
                current.origin().commit() +
                ". History was not downloaded.",
            "changes",
            "This is an immutable source archive; no local working-tree diff is available."
        );
        Path root = state.approvedRoot();
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

    String gitCommand(Path root, List<String> args) {
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
                "safe.directory=" + root.toString(),
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
