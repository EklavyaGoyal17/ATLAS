# ADR 011 — Source-supported architecture review

Context: name-only field diagrams and long-file heuristics do not explain circular dependencies or concentrated coupling. The local workspace needs actionable source evidence without claiming a compiler or runtime call graph.

Decision: analyze the approved redacted Java snapshot on demand. Build qualified declaration identities from packages and enclosing types, then resolve direct field/array-element types and record components using lexical declarations, explicit imports and package scope. Skip duplicate qualified declarations, unsupported scopes, external imports, type-parameter shadowing and inheritance ambiguity. Do not resolve wildcard/static imports, generic arguments or method-only dependencies. Malformed or withheld files cannot supply relationships.

Compute strongly connected components with iterative Kosaraju traversal. Report groups of at least two mutually reachable types; exclude self references. Group membership order is not an execution path. Fan-in/out count distinct neighboring types, so repeated fields do not inflate coupling. Five incoming or outgoing types triggers a review candidate; shared domain types may legitimately meet that threshold.

Bounds: one analysis at a time, 1000 types, 3000 declaration references, 20 groups and 20 coupling candidates. Group evidence is capped at 40/20 references; exports retain the bounded graph. Truncation, unresolved/external fields, skipped files and source lines are explicit. The report carries its immutable snapshot ID; the browser hides results for another snapshot and removes analysis/metadata/Git caches when the import is deleted. The backend keeps no additional report cache or persisted source corpus.

Alternatives: a compiler/type solver and build-classpath discovery could resolve more semantics but introduce dependency acquisition and unsafe execution pressure. A broad name-only graph would produce false links between packages. Neither is appropriate for this read-only local review boundary.

Consequences: declarations are evidence, not proof of compilation, runtime calls, a defect or a quality grade. An empty cycle list does not establish an acyclic repository. Non-Java formats remain searchable. The analyzer executes no repository code, network calls, builds, package installation or model transmission.

Validation: package/name collisions, nested imports, record/array references, generic and inherited shadowing, explicit external/static imports, duplicate declarations, bounds, malformed/withheld source and concurrency-budget release are covered. Forty seeded graphs are compared with an independent transitive-reachability oracle. Browser checks navigate real declarations and export the report tied to the approved snapshot.

Review date: 2027-01-19, or before adding another language, resolved build classpaths or automated refactoring.

Resolution reference: Java's [shadowing rules](https://docs.oracle.com/javase/specs/jls/se21/html/jls-6.html#jls-6.4.1) distinguish explicit single imports from imports on demand. An unrelated static wildcard import must not discard a known same-package field type; the analyzer still does not resolve members supplied by that wildcard.
