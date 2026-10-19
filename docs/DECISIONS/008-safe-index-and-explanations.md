# ADR 008 — Read-only source snapshots and consent-bound explanations

Context: imported repositories can contain secrets, malicious instructions, huge files, links and code that must never execute during analysis.

Decision: the operator configures one allowed root; the user approves a relative Git repository path. Walk without following links; reject escapes and special paths; skip hidden/generated/credential-named files; bound files, bytes, entries and depth. Keep a redacted UTF-8 snapshot in memory. Parse Java 21 syntax with JavaParser 3.28.2. Type edges and potential calls require unambiguous syntax evidence; dynamic dispatch, overload ambiguity, branches and runtime execution remain unknown.

Alternatives: repository builds and language-server startup would provide stronger symbol resolution but execute or acquire repository-controlled code. Regex-only Java analysis would overstate relationships. A remote embedding service would require another privacy boundary. Deterministic lexical retrieval is sufficient for the initial approved corpus.

Decision for model use: default to local source evidence. The typed provider interface has no command or file tools. A configured HTTPS API bridge or explicit loopback model bridge can receive up to five excerpts only after preview and per-request approval of content-hashed IDs. Provider outage, invalid citations or an unavailable model falls back to source evidence. Instruction-like files are excluded from retrieval. Citation validation checks identity, not semantic truth.

Tradeoffs: Java syntax support only; TypeScript and other allowed text files are searchable, without inferred call graphs. Secret/injection detection is heuristic. Concurrent hostile filesystem mutation is not fully eliminated by portable Java path checks; use a stable read-only checkout and operator-controlled parent directory. Git history/diff use fixed builtins with no shell, external diff, text conversion, pager or fsmonitor, and bounded output/time; no commands from the repository are executed.

Consequences: reindex after code changes. Delete/restart clears the index. The read-only Compose bind mount is available to the server, but no scanning occurs until approval. Use a dedicated clean checkout instead of a confidential work repository. There is no autonomous mutation capability to grant.

Review date: 2027-01-19, or before multi-user import.

Sources: [JavaParser](https://github.com/javaparser/javaparser), [Ollama local chat contract](https://docs.ollama.com/api/chat).
