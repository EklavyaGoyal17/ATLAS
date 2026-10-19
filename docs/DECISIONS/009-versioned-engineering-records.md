# ADR 009 — Versioned diagrams and engineering records

Context: architecture, incident reasoning and learning evidence should survive refresh and detect concurrent edits, without pretending to assess competence automatically.

Decision: PostgreSQL documents use UUIDs, explicit kinds, a 12 KiB JSON limit, compare-and-set versions, the last 20 revisions and a 1000-record workspace limit. The listing uses pages of 200 with optional kind filters; Engineering growth can reach older pages and export each page. Save and deletion retain metadata-only audit events, capped at the most recent 2000 sequence positions. A stale write returns 409 rather than silently replacing another version.

The SVG architecture editor supports mouse/touch movement, arrow-key movement, explicit connection controls, responsibilities, assumptions, six editable templates, revision previews and JSON/SVG export. A small native SVG implementation replaces the recommended React Flow dependency because the graph is deliberately capped at 24 components and 48 edges. It does not estimate throughput or failover guarantees. Single-instance findings are topology heuristics.

The growth interface saves journal entries, ADRs, learning evidence, postmortems and technical review answers. Fourteen curriculum areas link to actual files and exercises. Review feedback reports rubric-term coverage and asks for evidence; it cannot grade correctness or attribute learning to the user.

Alternatives: localStorage would lose cross-browser persistence and complicate deletion/privacy. Unbounded revision tables increase data retention. Automatic model scoring would imply confidence that is not supported by a simple rubric.

Consequences: export before pruning. Deleting a record cascades its revisions. Database backups may retain deleted content until their own retention expires. Journal bodies are excluded from repository retrieval and provider context. Access is one local engineer identity; multi-user authorization is a separate deployment requirement.

Review date: 2027-01-19, or when 1000 records/24 components becomes restrictive.
