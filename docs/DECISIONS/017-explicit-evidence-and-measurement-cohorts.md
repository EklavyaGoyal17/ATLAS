# ADR 017: Make measurements, incident briefs and review evidence explicit

Status: accepted, 22 October 2026.

## Context

A full check against the master prompt found four partial requirements: HTTP family aggregates could hide the slow payment operation, the copilot could not summarize saved incidents, review feedback only counted terms, and ADR fields were displayed but not required by the API. Completing these requirements must preserve local privacy, bounded resources and honest uncertainty.

## Decision

Retain existing global HTTP metrics and add a fixed vocabulary of 92 operation labels, with at most 100 latency samples per operation. Payment authorization, detail, capture, refund and reversal have separate templates. Other operations retain fixed families; unknown methods/instrumentation labels fold into fixed buckets. Raw paths, IDs, queries and bodies never become labels. Lifetime requests/errors and retained percentile start-time windows are separately named. Health, observability polling, unauthenticated traffic and early body/rate admission rejections are outside this measured handler cohort. The dashboard orders observed rows by retained p95 without claiming a controlled comparison or SLO.

Add a deterministic incident-copilot endpoint over the existing saved investigation evidence. Calculate baseline/affected medians, failed sample counts, retained ERROR spans and the slowest retained child; cite actual trace/span IDs. Limit input to 128 samples per cohort and 128 retained spans, and output to five findings and ten citations. Do not read journal/postmortem bodies, quote free-form hypotheses as established facts, or call a provider. Legacy, missing, deleted, truncated and unrelated evidence have explicit outcomes. The frontend keys queries by local identity and investigation and rejects mismatched IDs.

Give each technical-review area a concrete engineering claim, failure case, public implementation/test reference and verification action. Exact reference matching, a bounded user observation and an inspection acknowledgement can establish only reference-matched self-reported evidence. Keyword coverage remains advisory, human correctness verification remains outstanding, and no competence score is produced. Save the structured evidence and limitations with the review, within the existing document byte limit.

Require Context, Decision, Alternatives, Tradeoffs, Consequences and Review date on all new ADR writes and updates. Each required field is a nonblank string of at most 1200 characters. Review date is an actual Gregorian `yyyy-MM-dd` date in years 0001–9999. The API checks before any database mutation; the form uses the same calendar/required-field policy. Existing incomplete records remain readable and exports retain them. Editing requires completing the fields; additional bounded legacy fields remain allowed. No migration rewrites user decisions.

## Alternatives

Recording concrete URLs would create unbounded and potentially confidential metric labels. Sending incident or journal text to a model would require a separate consent/evidence protocol and add uncertainty to arithmetic that is deterministic. Grading keyword matches as correctness would misrepresent learning. Silently filling old ADR fields would invent the user's reasoning. Shared deployment, live-provider quality and broad capacity testing remain independent operating work.

## Consequences

The local platform closes the four audited capability gaps without new dependencies, external transmission or database migrations. JVM metrics still reset on restart; saved incident summaries can be recomputed identically after restart. Displayed evidence guides inspection and reproducible tests, while users remain responsible for validating conclusions and their own understanding.
