import { useState } from "react";
import { Notice } from "./ui";
import { timestamp } from "./api";
import { useDocuments, download, type Document } from "./workspace";
// These are reviewed public ATLAS examples, independent of imported snapshots.
// No URL is derived from a repository import, journal record, or user answer.
const publicAtlas = "https://github.com/EklavyaGoyal17/ATLAS/blob/main/";
const walkthroughs = {
  payments: {
    path: "docs/DEMO.md#runnable-payment-lifecycle-and-idempotency-under-pressure",
    title: "Payment lifecycle and idempotency",
  },
  rollback: {
    path: "docs/DEMO.md#runnable-atomic-rollback-and-state-recovery",
    title: "Atomic rollback and state recovery",
  },
  delivery: {
    path: "docs/DEMO.md#runnable-worker-interruption-and-durable-recovery",
    title: "Worker interruption and durable recovery",
  },
  design: {
    path: "docs/ARCHITECTURE.md#trust-and-transaction-boundaries",
    title: "Trust and transaction boundaries",
  },
  incidents: {
    path: "docs/DEMO.md#demo-3--measured-incident-investigation",
    title: "Measured incident investigation",
  },
  source: {
    path: "docs/DEMO.md#demo-4--understand-the-atlas-codebase",
    title: "Approved codebase investigation",
  },
  backups: {
    path: "docs/RECOVERY.md#application-created-backup-and-restored-api-readback",
    title: "Isolated application backup and readback",
  },
  assistant: {
    path: "docs/DEMO.md#demo-5--grounded-technical-review",
    title: "Grounded technical review",
  },
};
const curriculum = [
  {
    area: "Java",
    concept: "Represent money with integer minor units and explicit currency.",
    source: "backend/src/main/java/dev/atlas/payments/PaymentModels.java",
    walkthrough: walkthroughs.payments,
    exercise: "Add a currency safely; explain rounding at the boundary.",
    question:
      "Why must money avoid binary floating-point, and where is currency enforced?",
    rubric: ["integer", "currency", "rounding"],
  },
  {
    area: "Spring Boot",
    concept: "Transaction proxies wrap public service boundaries.",
    source: "backend/src/main/java/dev/atlas/payments/PaymentService.java",
    walkthrough: walkthroughs.rollback,
    exercise:
      "Explain why calling a transactional method on this bypasses a proxy.",
    question:
      "When does the payment transaction commit, and what does self-invocation change?",
    rubric: ["proxy", "commit", "rollback"],
  },
  {
    area: "SQL",
    concept: "Constraints complement application validation.",
    source: "backend/src/main/resources/db/migration/V1__payment_core.sql",
    walkthrough: walkthroughs.rollback,
    exercise:
      "Design an additive migration and a backfill without blocking writes.",
    question: "How would you migrate a large payment table without downtime?",
    rubric: ["additive", "backfill", "lock"],
  },
  {
    area: "Concurrency",
    concept: "Key locks and payment row locks serialize different invariants.",
    source: "backend/src/main/java/dev/atlas/payments/PaymentStore.java",
    walkthrough: walkthroughs.payments,
    exercise:
      "Run the concurrent capture integration test and explain its linearization point.",
    question:
      "What prevents two concurrent captures from producing two ledger postings?",
    rubric: ["lock", "transaction", "constraint"],
  },
  {
    area: "Networking",
    concept: "A timeout can leave the business outcome unknown.",
    source: "scripts/load.mjs",
    walkthrough: walkthroughs.delivery,
    exercise: "Drop a response after commit, then retry the original key.",
    question:
      "How can a caller recover when the response is lost after a successful database commit?",
    rubric: ["same key", "replay", "unknown"],
  },
  {
    area: "APIs",
    concept: "A repeated key must describe the same canonical intent.",
    source: "backend/src/main/java/dev/atlas/payments/PaymentService.java",
    walkthrough: walkthroughs.payments,
    exercise: "Change the amount while keeping the original idempotency key.",
    question:
      "How do you guarantee the same payment request is not processed twice?",
    rubric: ["advisory", "fingerprint", "transaction"],
  },
  {
    area: "Distributed systems",
    concept:
      "Outbox publication and consumer inbox address separate atomicity gaps.",
    source: "backend/src/main/java/dev/atlas/eventing/DeliveryService.java",
    walkthrough: walkthroughs.delivery,
    exercise:
      "Crash after commit, advance the lease, and verify one notification.",
    question:
      "What happens if the worker commits its effect and fails before acknowledging?",
    rubric: ["inbox", "lease", "duplicate"],
  },
  {
    area: "System design",
    concept:
      "A modular monolith preserves local transactions and limits operating cost.",
    source: "docs/DECISIONS/001-modular-monolith.md",
    walkthrough: walkthroughs.design,
    exercise:
      "Design the consistency boundary if the ledger becomes a separate service.",
    question:
      "Why is the ledger a module rather than a separate deployed service?",
    rubric: ["atomic", "operating", "boundary"],
  },
  {
    area: "Testing",
    concept: "Concurrent and rollback tests check observable invariants.",
    source:
      "backend/src/test/java/dev/atlas/payments/PaymentIntegrationTest.java",
    walkthrough: walkthroughs.rollback,
    exercise: "Propose a race that would pass sequential unit tests.",
    question: "Which test would detect a refund race that unit tests miss?",
    rubric: ["concurrent", "remaining", "balance"],
  },
  {
    area: "Debugging",
    concept: "Use correlation and trace IDs to connect observations.",
    source: "backend/src/main/java/dev/atlas/observability/Telemetry.java",
    walkthrough: walkthroughs.incidents,
    exercise:
      "Compare baseline and injected database latency and inspect child spans.",
    question:
      "How would you distinguish a slow query from connection acquisition delay?",
    rubric: ["connection", "query", "baseline"],
  },
  {
    area: "Security",
    concept:
      "Imported source is untrusted data and must stay inside an approved root.",
    source: "backend/src/main/java/dev/atlas/intelligence/RepositoryIndex.java",
    walkthrough: walkthroughs.source,
    exercise: "Try a traversal path and inspect the import security tests.",
    question:
      "How do you prevent a repository from executing instructions or exposing secrets?",
    rubric: ["read-only", "redact", "approval"],
  },
  {
    area: "Cloud infrastructure",
    concept: "One database is an explicit availability boundary.",
    source: "compose.yaml",
    walkthrough: walkthroughs.backups,
    exercise:
      "Write a backup and restore procedure; test it only on a disposable database.",
    question:
      "What would fail first under ten times the traffic, and what evidence is missing?",
    rubric: ["pool", "measure", "database"],
  },
  {
    area: "Observability",
    concept: "Percentiles require a known observation window and sample scope.",
    source:
      "backend/src/main/java/dev/atlas/observability/IncidentService.java",
    walkthrough: walkthroughs.incidents,
    exercise: "Run lock contention, compare traces, and write a postmortem.",
    question:
      "Why is the displayed p95 insufficient to claim a production SLO?",
    rubric: ["sample", "window", "workload"],
  },
  {
    area: "AI-assisted engineering",
    concept: "Citation validation proves existence, not semantic correctness.",
    source:
      "backend/src/main/java/dev/atlas/intelligence/AssistantService.java",
    walkthrough: walkthroughs.assistant,
    exercise:
      "Review the prompt-injection and outage tests before enabling a provider.",
    question:
      "How do you verify a model answer without blindly trusting citations?",
    rubric: ["source", "test", "uncertainty"],
  },
];
function PublicLearningEvidence({
  item,
}: {
  item: (typeof curriculum)[number];
}) {
  return (
    <>
      <p>
        Public ATLAS implementation:{" "}
        <a
          href={`${publicAtlas}${item.source}`}
          target="_blank"
          rel="noopener noreferrer"
        >
          <code>{item.source}</code>
        </a>
      </p>
      <p>
        <a
          href={`${publicAtlas}${item.walkthrough.path}`}
          target="_blank"
          rel="noopener noreferrer"
        >
          Open walkthrough: {item.walkthrough.title}
        </a>
      </p>
    </>
  );
}
const fields: Record<string, string[]> = {
  JOURNAL: ["Problem", "Investigation", "Solution", "Lesson", "Next action"],
  ADR: [
    "Context",
    "Decision",
    "Alternatives",
    "Tradeoffs",
    "Consequences",
    "Review date",
  ],
  LEARNING: ["Area", "Goal", "Evidence", "Status"],
  POSTMORTEM: ["Incident", "Hypothesis", "Validation", "Corrective action"],
};
export function Growth({ token }: { token: string }) {
  const [page, setPage] = useState(0);
  const docs = useDocuments(token, page);
  const learning = useDocuments(token, 0, "LEARNING");
  const [kind, setKind] = useState("JOURNAL");
  const [title, setTitle] = useState("");
  const [body, setBody] = useState<Record<string, unknown>>({});
  const [editing, setEditing] = useState<Document | null>(null);
  const [message, setMessage] = useState("");
  const [safe, setSafe] = useState(false);
  const [topic, setTopic] = useState(5);
  const [answer, setAnswer] = useState("");
  const [evaluated, setEvaluated] = useState(false);
  const active = curriculum[topic];
  const text = answer.toLowerCase();
  function reset() {
    setEditing(null);
    setTitle("");
    setBody({});
    setSafe(false);
  }
  function edit(doc: Document) {
    setEditing(doc);
    setKind(Object.hasOwn(fields, doc.kind) ? doc.kind : "JOURNAL");
    setTitle(doc.title);
    setBody(doc.body);
    setSafe(false);
  }
  async function save() {
    try {
      await docs.save.mutateAsync({
        id: editing?.id ?? crypto.randomUUID(),
        kind: editing?.kind ?? kind,
        title,
        body,
        version: editing?.version ?? 0,
      });
      reset();
      setMessage("Engineering note saved.");
    } catch {
      /* shared notice */
    }
  }
  return (
    <div className="module-stack">
      <Notice
        error={docs.query.error || docs.save.error || docs.remove.error}
      />
      <div className="callout">
        Internship-safe journal: record generalized learning, public concepts
        and personal goals. Exclude proprietary information, internal code,
        credentials, customer data, production logs and confidential incidents.
        Notes stay in this local database and are never included in assistant
        context.
      </div>
      <section className="panel padded">
        <h2>{editing ? "Edit engineering record" : "Record the reasoning"}</h2>
        <div className="form-row">
          <label>
            Record type
            <select
              value={kind}
              disabled={!!editing}
              onChange={(e) => {
                setKind(e.target.value);
                setBody({});
              }}
            >
              {Object.keys(fields).map((k) => (
                <option key={k}>{k}</option>
              ))}
            </select>
          </label>
          <label>
            Record title
            <input
              value={title}
              maxLength={120}
              onChange={(e) => setTitle(e.target.value)}
            />
          </label>
        </div>
        {fields[kind].map((field) => (
          <label key={field}>
            {field}
            <textarea
              value={String(body[field] ?? "")}
              maxLength={1200}
              onChange={(e) =>
                setBody((b) => ({ ...b, [field]: e.target.value }))
              }
            />
          </label>
        ))}
        <label className="check-label">
          <input
            type="checkbox"
            checked={safe}
            onChange={(e) => setSafe(e.target.checked)}
          />
          This note contains only generalized, non-confidential information
        </label>
        <div className="button-row">
          <button
            className="primary"
            disabled={!safe || !title.trim() || docs.save.isPending}
            onClick={() => void save()}
          >
            Save engineering record
          </button>
          <button className="secondary" onClick={reset}>
            New blank record
          </button>
        </div>
        {message && <p role="status">{message}</p>}
      </section>
      <section className="panel padded" aria-label="Learning roadmap">
        <h2>Learning roadmap · concept to code</h2>
        <p>
          Progress is self-reported and saved with your evidence; opening a
          lesson does not mark it complete.
        </p>
        <p className="muted">
          Source and walkthrough links open the public ATLAS examples on GitHub
          in a new tab and follow the main branch. They do not open or import
          your selected repository, execute an exercise, or change your recorded
          progress.
        </p>
        <div className="learning-grid">
          {curriculum.map((item, i) => {
            const existing = learning.query.data?.find(
              (d) =>
                d.kind === "LEARNING" && d.title === `Roadmap: ${item.area}`,
            );
            return (
              <article key={item.area}>
                <h3>{item.area}</h3>
                <p>{item.concept}</p>
                <PublicLearningEvidence item={item} />
                <p>{item.exercise}</p>
                <div className="button-row">
                  <button
                    className="secondary"
                    onClick={() => {
                      reset();
                      setKind("LEARNING");
                      setTitle(`Roadmap: ${item.area}`);
                      setBody(
                        existing?.body ?? {
                          Area: item.area,
                          Goal: item.exercise,
                          Evidence: "",
                          Status: "Practicing",
                        },
                      );
                      setEditing(existing ?? null);
                      window.scrollTo({ top: 0, behavior: "smooth" });
                    }}
                  >
                    {existing
                      ? String(existing.body.Status ?? "Recorded")
                      : "Record practice evidence"}
                  </button>
                  <button
                    className="text-button"
                    onClick={() => {
                      setTopic(i);
                      setAnswer("");
                      setEvaluated(false);
                      document
                        .getElementById("review-mode")
                        ?.scrollIntoView({ behavior: "smooth" });
                    }}
                  >
                    Review this concept
                  </button>
                </div>
              </article>
            );
          })}
        </div>
      </section>
      <section
        className="panel padded"
        id="review-mode"
        aria-label="Technical review mode"
      >
        <h2>Technical review mode</h2>
        <p>
          Deterministic questions are grounded in the implementation. Feedback
          checks rubric term coverage and suggests evidence to verify; it does
          not grade correctness or infer your skill.
        </p>
        <label htmlFor="review-area">Review area</label>
        <select
          id="review-area"
          value={topic}
          onChange={(e) => {
            setTopic(Number(e.target.value));
            setAnswer("");
            setEvaluated(false);
          }}
        >
          {curriculum.map((c, i) => (
            <option value={i} key={c.area}>
              {c.area}
            </option>
          ))}
        </select>
        <h3>{active.question}</h3>
        <PublicLearningEvidence item={active} />
        <label>
          Your technical answer
          <textarea
            value={answer}
            maxLength={4000}
            onChange={(e) => {
              setAnswer(e.target.value);
              setEvaluated(false);
            }}
          />
        </label>
        <button
          className="primary"
          disabled={!answer.trim()}
          onClick={() => setEvaluated(true)}
        >
          Evaluate against rubric
        </button>
        {evaluated && (
          <div className="answer">
            <h3>Evidence checklist</h3>
            {active.rubric.map((term) => (
              <p key={term}>
                {text.includes(term) ? "Mentioned" : "Discuss and substantiate"}
                : <strong>{term}</strong>. Verify the claim against the linked
                public implementation and its tests.
              </p>
            ))}
            <p className="callout">
              Keyword presence is not proof of understanding. Explain the
              failure case, identify the invariant, and demonstrate it with a
              test.
            </p>
            <button
              className="secondary"
              disabled={docs.save.isPending}
              onClick={() =>
                docs.save.mutate(
                  {
                    id: crypto.randomUUID(),
                    kind: "REVIEW",
                    title: `Review: ${active.area}`,
                    version: 0,
                    body: {
                      question: active.question,
                      answer,
                      rubric: active.rubric,
                      source: active.source,
                      feedback:
                        "Term coverage only; correctness requires human review.",
                    },
                  },
                  { onSuccess: () => setMessage("Review answer saved.") },
                )
              }
            >
              Save review answer
            </button>
          </div>
        )}
      </section>
      <section className="panel padded">
        <h2>Your engineering records</h2>
        <p>
          {docs.query.data?.length ?? 0} records on page {page + 1} (up to 200
          per page); includes saved incident investigations and architecture
          versions.
        </p>
        <div className="button-row">
          <button
            className="secondary"
            disabled={page === 0 || docs.query.isFetching}
            onClick={() => setPage((p) => p - 1)}
          >
            Previous records
          </button>
          <button
            className="secondary"
            disabled={
              page >= 4 ||
              (docs.query.data?.length ?? 0) < 200 ||
              docs.query.isFetching
            }
            onClick={() => setPage((p) => p + 1)}
          >
            Older records
          </button>
          <button
            className="secondary"
            disabled={!docs.query.data?.length}
            onClick={() =>
              download(
                "atlas-engineering-records.json",
                JSON.stringify(docs.query.data, null, 2),
              )
            }
          >
            Export this page JSON
          </button>
          <button
            className="secondary"
            disabled={!docs.query.data?.length}
            onClick={() =>
              download(
                "atlas-engineering-records.md",
                (docs.query.data ?? [])
                  .map(
                    (d) =>
                      `# ${d.title}\n\n${d.kind} · version ${d.version}\n\n${Object.entries(
                        d.body,
                      )
                        .map(
                          ([k, v]) =>
                            `## ${k}\n\n${typeof v === "string" ? v : JSON.stringify(v, null, 2)}`,
                        )
                        .join("\n\n")}`,
                  )
                  .join("\n\n---\n\n"),
                "text/markdown",
              )
            }
          >
            Export decision records Markdown
          </button>
        </div>
        {docs.query.data?.map((d) => (
          <details className="record" key={d.id}>
            <summary>
              {d.title} · {d.kind} · v{d.version} · {timestamp(d.updatedAt)}
            </summary>
            <pre>{JSON.stringify(d.body, null, 2)}</pre>
            <div className="button-row">
              {Object.hasOwn(fields, d.kind) && (
                <button
                  className="secondary"
                  onClick={() => {
                    edit(d);
                    window.scrollTo({ top: 0, behavior: "smooth" });
                  }}
                >
                  Edit record
                </button>
              )}
              <button
                className="secondary"
                onClick={() => docs.remove.mutate(d.id)}
              >
                Delete record and revisions
              </button>
            </div>
          </details>
        ))}
        {!docs.query.data?.length && (
          <p>
            No saved records. Start with a problem you investigated and the
            evidence that changed your mind.
          </p>
        )}
      </section>
    </div>
  );
}
