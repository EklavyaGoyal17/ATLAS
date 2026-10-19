package dev.atlas.intelligence;

import dev.atlas.platform.ApiException;
import dev.atlas.workspace.Documents;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {

    public record Answer(
        String mode,
        String answer,
        List<ModelProvider.Context> citations,
        List<String> uncertainty
    ) {}

    private final RepositoryIndex index;
    private final ModelProvider provider;
    private final Documents documents;
    private final Semaphore gate = new Semaphore(2);

    public AssistantService(
        RepositoryIndex index,
        ModelProvider provider,
        Documents documents
    ) {
        this.index = index;
        this.provider = provider;
        this.documents = documents;
    }

    public Map<String, Object> configuration() {
        return Map.of(
            "configured",
            provider.configured(),
            "retrieval",
            "Deterministic lexical ranking of approved, redacted source chunks; no embedding transmission.",
            "tools",
            List.of(),
            "consent",
            "External/local provider use requires per-request approval after context preview."
        );
    }

    public List<ModelProvider.Context> retrieve(String question) {
        var words = new HashSet<>(
            List.of(question.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+"))
        );
        words.removeAll(
            Set.of(
                "",
                "the",
                "a",
                "is",
                "of",
                "and",
                "to",
                "in",
                "this",
                "does",
                "what",
                "where",
                "how",
                "which",
                "why",
                "for"
            )
        );
        if (words.stream().anyMatch(s -> s.contains("idempoten"))) words.add(
            "idempotent"
        );
        if (words.contains("request") || words.contains("enters")) words.add(
            "mapping"
        );
        if (words.contains("commit")) words.add("transactional");
        if (words.contains("authorization")) words.add("authorize");
        record Candidate(ModelProvider.Context context, int score) {}
        var candidates = new ArrayList<Candidate>();
        for (var source : index.current().files()) {
            if (
                source
                    .warnings()
                    .stream()
                    .anyMatch(w -> w.startsWith("Instruction-like")) ||
                source.content().startsWith("[File withheld")
            ) continue;
            String[] lines = source.content().split("\\R", -1);
            for (int offset = 0; offset < lines.length; offset += 22) {
                int start = offset;
                int end = Math.min(lines.length, start + 28);
                String text = String.join(
                    "\n",
                    Arrays.copyOfRange(lines, start, end)
                );
                String searchable = (source.path() + "\n" + text).toLowerCase(
                    Locale.ROOT
                );
                int score = words
                    .stream()
                    .mapToInt(w -> searchable.contains(w) ? 1 : 0)
                    .sum();
                if (
                    text.contains("@Transactional") &&
                    (words.contains("transaction") ||
                        words.contains("commit") ||
                        words.contains("boundary"))
                ) score += 3;
                if (
                    text.contains("Mapping") &&
                    (words.contains("request") ||
                        words.contains("enter") ||
                        words.contains("mapping"))
                ) score += 3;
                if (score > 0) candidates.add(
                    new Candidate(
                        new ModelProvider.Context(
                            sourceId(source.path(), start, text),
                            source.path(),
                            start + 1,
                            end,
                            text
                        ),
                        score
                    )
                );
            }
        }
        return candidates
            .stream()
            .sorted(
                Comparator.comparingInt(Candidate::score)
                    .reversed()
                    .thenComparing(c -> c.context().path())
                    .thenComparingInt(c -> c.context().startLine())
            )
            .limit(5)
            .map(Candidate::context)
            .toList();
    }

    public Answer ask(
        String question,
        boolean useProvider,
        boolean approved,
        List<String> approvedIds
    ) {
        var context = retrieve(question);
        var uncertainty = new ArrayList<>(
            List.of(
                "Source snapshots may be stale. Declared dependencies do not prove runtime call paths. Validate hypotheses with tests and traces.",
                "Redaction is heuristic. Review every excerpt before permitting provider transmission."
            )
        );
        if (!useProvider) return fallback(
            context,
            uncertainty,
            "Deterministic source evidence; no model used."
        );
        if (
            !approved ||
            approvedIds == null ||
            !new HashSet<>(approvedIds).equals(
                context
                    .stream()
                    .map(ModelProvider.Context::id)
                    .collect(java.util.stream.Collectors.toSet())
            )
        ) throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "CONSENT_REQUIRED",
            "Preview the exact retrieved source IDs and approve this transmission first."
        );
        if (context.isEmpty()) return fallback(
            context,
            uncertainty,
            "No relevant safe evidence found; no provider request sent."
        );
        if (!provider.configured()) return fallback(
            context,
            uncertainty,
            "Provider is not configured; local evidence remains available."
        );
        if (!gate.tryAcquire()) return fallback(
            context,
            uncertainty,
            "Provider busy; local evidence remains available."
        );
        try {
            documents.audit(
                "MODEL_TRANSMISSION",
                index.current().id().toString()
            );
            var completion = provider.explain(
                Redaction.mask(question),
                context
            );
            var allowed = context
                .stream()
                .map(ModelProvider.Context::id)
                .toList();
            if (
                completion.citationIds().isEmpty() ||
                !allowed.containsAll(completion.citationIds())
            ) throw new IllegalStateException("Unsupported citations");
            uncertainty.add(
                "Model explanation is a hypothesis. Citation ID validation establishes the source exists, not that the explanation follows from it."
            );
            return new Answer(
                "PROVIDER",
                Redaction.mask(completion.answer()),
                context
                    .stream()
                    .filter(c -> completion.citationIds().contains(c.id()))
                    .toList(),
                uncertainty
            );
        } catch (Exception e) {
            return fallback(
                context,
                uncertainty,
                "Provider unavailable or invalid citations; local evidence remains available."
            );
        } finally {
            gate.release();
        }
    }

    private Answer fallback(
        List<ModelProvider.Context> context,
        List<String> uncertainty,
        String reason
    ) {
        String text =
            reason +
            "\n\n" +
            (context.isEmpty()
                ? "No matching safe source excerpts. Try a specific class, method, exception or concept."
                : "Inspect the cited excerpts below. They are the evidence retrieved for this question; no generated explanation or inferred call relationship is asserted.");
        return new Answer("LOCAL_EVIDENCE", text, context, uncertainty);
    }

    private String sourceId(String path, int line, String text) {
        try {
            return (
                "S" +
                HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                        (path + "|" + line + "|" + text).getBytes(
                            java.nio.charset.StandardCharsets.UTF_8
                        )
                    )
                )
            );
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
