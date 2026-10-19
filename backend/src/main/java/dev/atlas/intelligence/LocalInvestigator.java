package dev.atlas.intelligence;

import java.util.*;

/** Source facts and conditional investigation steps; never executes or generates code. */
final class LocalInvestigator {

    private LocalInvestigator() {}

    static String explain(
        RepositoryIndex.Snapshot snapshot,
        String question,
        List<ModelProvider.Context> context
    ) {
        var facts = new LinkedHashSet<String>();
        snapshot
            .symbols()
            .stream()
            .filter(s -> Set.of("endpoint", "transaction").contains(s.kind()))
            .filter(s -> cited(context, s.path(), s.line()))
            .limit(8)
            .forEach(s ->
                facts.add(
                    "- " +
                        s.kind() +
                        " declaration: " +
                        s.name() +
                        " at " +
                        s.path() +
                        ":" +
                        s.line()
                )
            );
        snapshot
            .calls()
            .stream()
            .filter(
                c ->
                    cited(context, c.path(), c.line()) &&
                    cited(context, c.targetPath(), c.targetLine())
            )
            .limit(6)
            .forEach(c ->
                facts.add(
                    "- Potential call: " +
                        c.caller() +
                        " → " +
                        c.callee() +
                        " (" +
                        c.path() +
                        ":" +
                        c.line() +
                        "; " +
                        c.targetPath() +
                        ":" +
                        c.targetLine() +
                        ")"
                )
            );
        String evidence = facts.isEmpty()
            ? "Matching source excerpts are available below; no supported endpoint, transaction or call fact was found inside these excerpts."
            : "Source facts (static analysis, limited to cited excerpts):\n" +
              String.join("\n", facts);
        var plan = new ArrayList<String>();
        plan.add(
            "- Inspect the complete enclosing method and its callers; excerpts can omit relevant guards and exception handling."
        );
        if (facts.stream().anyMatch(f -> f.startsWith("- transaction"))) {
            plan.add(
                "- Verify proxy invocation, rollback rules and commit behavior with an integration test; an annotation alone does not prove that a transaction runs."
            );
        }
        String intent = question.toLowerCase(Locale.ROOT);
        if (
            intent.matches(
                "(?s).*(test|concurren|race|refund|idempoten|duplicate).*"
            )
        ) {
            plan.add(
                "- Test one successful operation, invalid input, duplicate retries with the same intent, and a changed payload using the same key; check durable state and side effects after each."
            );
            plan.add(
                "- Synchronize competing requests with a barrier, assert the allowed outcomes and final invariant, then inject a failure before commit and a lost response after commit. These are proposed tests, not executed results."
            );
        } else if (
            intent.matches("(?s).*(architect|tradeoff|queue|design|review).*")
        ) {
            plan.add(
                "- Review ownership, failure boundaries, retry/idempotency behavior, consistency and operational cost. Compare alternatives against an explicit workload; these are review questions, not measured findings."
            );
        } else {
            plan.add(
                "- Reproduce the question with a focused test, follow a correlated runtime trace and compare the observed path with the declarations above."
            );
        }
        return (
            evidence +
            "\n\nSuggested investigation (requires your validation):\n" +
            String.join("\n", plan)
        );
    }

    private static boolean cited(
        List<ModelProvider.Context> context,
        String path,
        int line
    ) {
        return context
            .stream()
            .anyMatch(
                c ->
                    c.path().equals(path) &&
                    c.startLine() <= line &&
                    c.endLine() >= line
            );
    }
}
