# ADR 010 — Preserve bounded investigation evidence

Context: the live OpenTelemetry ring expires after 1000 spans or JVM restart. Persisted sample IDs alone cannot support later trace investigation or an exported postmortem review.

Decision: capture each controlled sample's available spans at sample completion. Each sample starts an independent root trace and retains the initiating request's log correlation ID. Save an immutable investigation snapshot and its spans in `incident_evidence`, atomically with the existing investigation document, revision and audit. Migration V5 adds an evidence row keyed to the document with deletion cascade. Public document writes cannot create or edit system-managed investigations; engineers record their reasoning in postmortems.

Bounds: retain at most 128 spans and 128000 UTF-8 bytes per investigation. Application and database constraints enforce the byte cap; responses expose truncation. Evidence belongs to the existing 1000-document workspace budget. Deleting an investigation deletes its evidence; prior exports/backups have independent retention. General request traces, SQL, request bodies, credentials and repository content are not archived. This is a bounded sample snapshot, not a complete tracing backend.

Alternatives: persist every live span, or add an OTLP collector and external storage. Both introduce broader retention, privacy and operational work than this single-user controlled-incident workflow needs. Embedding spans in the document would exceed its existing 12 KiB limit and duplicate evidence in every revision.

Tradeoffs: historical investigations have no recoverable child spans; the evidence API returns `available=false` explicitly. Capture cannot reconstruct spans already evicted from the live ring. UI controls distinguish saved evidence from current live telemetry, list saved investigations by page, filter samples and export the bounded snapshot. There is no fleet collector, unrestricted trace search or production monitoring claim.

Validation: integration checks cover atomic rollback, count/byte bounds, unrelated-trace exclusion, authentication, old-record behavior and deletion. Migration checks preserve V4 records without inventing historical evidence. Browser workflows reopen/filter/export after reload; the actual API restart script compares evidence before and after a fresh JVM. The isolated logical restore includes an evidence fixture and cascade check.

Review date: 2027-01-19, or before adding persistent general telemetry, shared identities or an external collector.
