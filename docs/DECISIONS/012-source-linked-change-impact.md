# ADR 012 — Source-linked change impact

Context: the architecture review exposes resolved Java declarations and references, but a developer still has to trace incoming relationships manually when planning a change. An inferred runtime blast radius or automatically selected regression suite would overstate this syntax-only graph.

Decision: accept one approved snapshot ID and one to twenty exact indexed file paths. All resolved types in those files become seeds. Traverse incoming declaration edges with iterative multi-source breadth-first search. Return dependent types in distance/identity order, with one deterministic shortest path from each candidate toward a selected seed. Selected types are excluded from candidates even when a cycle leads back to them. Duplicate references do not duplicate candidates. Distance counts declaration references, not calls or service hops.

Reuse the existing qualified architecture graph and its one-analysis concurrency gate. Unknown paths return 400 before graph analysis. A request or analysis referring to another snapshot returns 409. No source file is refreshed, repository command executed, dependency installed or model contacted. There is no additional report cache, stored source corpus or database migration.

Bounds: at most 1000 analyzed types and 3000 declaration references inherited from the architecture analysis, 200 returned candidates and 20 edges per returned path. Compute the total reachable count in the bounded graph even when the output is capped; preserve the full shortest distance when evidence is capped. Unrepresented files, unresolved fields, skipped files, graph truncation and result/path truncation remain explicit. The inherited graph flag may also reflect architecture cycle/coupling display caps, so it is conservative rather than a precise reason for missing graph facts.

The console offers manual analysis, exact indexed-file selection, source links and a JSON export. Selection changes invalidate results immediately. Intent versions reject late responses; import/delete unmount the explorer and a new snapshot creates a fresh instance. Exports require the current snapshot and selection. Searching the file list alone does not change the selection.

Alternatives: a runtime trace graph covers only executed paths; compiler/build integration resolves more semantics but requires separate read-only tooling boundaries. Git-diff-driven selection would add a different question about working-tree freshness. These are not prerequisites for reviewing a selected immutable snapshot.

Consequences: candidates are review leads, not confirmed propagation, defects, compilation proof or test coverage. Method-only, generic, inherited, external and unsupported-language relationships can be absent. A selected file without resolved types has unknown impact under this analysis, rather than proven zero impact. Review actual source and execute appropriate tests before changing code.

Validation: independent seeded shortest-distance/path oracles, multi-seed cycles, repeated edges, package identity, disconnected types, empty/unrepresented files and output/path caps; HTTP authentication, validation, exact source evidence, reimport and deletion; Chromium/Firefox source navigation, exports and delayed-response invalidation. Executed results are recorded in TESTING.

Review date: 2027-01-19, or before adding runtime impact, compiler integration or automatic test selection.
