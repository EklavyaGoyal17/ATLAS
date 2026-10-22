const paymentTests =
  "backend/src/test/java/dev/atlas/payments/PaymentIntegrationTest.java";
const engineeringTests =
  "backend/src/test/java/dev/atlas/workspace/EngineeringIntegrationTest.java";
const intelligenceTests =
  "backend/src/test/java/dev/atlas/intelligence/IntelligenceTest.java";

export type ReviewCriterion = {
  area: string;
  claim: string;
  source: string;
  test: string;
  failureCase: string;
  verification: string;
  terms: string[];
};

// Reviewed public examples only. References are exact paths with optional test
// method anchors, never URLs or paths supplied by an imported repository.
export const reviewCriteria: ReviewCriterion[] = [
  {
    area: "Java",
    claim:
      "Integer minor units and explicit currency keep payment accounting exact within supported bounds.",
    source: "backend/src/main/java/dev/atlas/payments/PaymentModels.java",
    test: `${paymentTests}#trustBoundaryValidationAndAuthorization`,
    failureCase:
      "A fractional minor unit, unsupported currency or out-of-range amount must be rejected before posting.",
    verification:
      "Inspect amount/currency validation and run the boundary test; record a rejected request and unchanged ledger.",
    terms: ["integer", "currency", "rounding"],
  },
  {
    area: "Spring Boot",
    claim:
      "A public transactional service boundary commits payment state and related effects together.",
    source: "backend/src/main/java/dev/atlas/payments/PaymentService.java",
    test: `${paymentTests}#postingFailureRollsBackStateAuditAndIdempotency`,
    failureCase:
      "A posting failure must not leave payment state, audit or replay records partially committed.",
    verification:
      "Trace the proxy boundary and run the posting failure test; compare all durable effects after rollback.",
    terms: ["proxy", "commit", "rollback"],
  },
  {
    area: "SQL",
    claim:
      "Database constraints defend payment invariants independently of normal application validation.",
    source: "backend/src/main/resources/db/migration/V1__payment_core.sql",
    test: `${paymentTests}#migrationAndDatabaseConstraintsProtectDurableState`,
    failureCase:
      "Direct invalid SQL must fail constraints; an additive migration can still block writers if lock behavior is ignored.",
    verification:
      "Inspect constraint definitions and run the direct SQL test on atlas_test; propose and measure a separate backfill plan.",
    terms: ["additive", "backfill", "lock"],
  },
  {
    area: "Concurrency",
    claim:
      "Row/advisory locking and unique posting constraints prevent two successful captures of one payment.",
    source: "backend/src/main/java/dev/atlas/payments/PaymentStore.java",
    test: `${paymentTests}#differentKeysCannotCaptureTwice`,
    failureCase:
      "Two capture requests with different keys racing on one authorization must not create duplicate capture postings.",
    verification:
      "Run the concurrent capture test and inspect responses plus ledger rows; identify the locking and commit boundary.",
    terms: ["lock", "transaction", "constraint"],
  },
  {
    area: "Networking",
    claim:
      "An unchanged idempotency key and intent let a caller retrieve a committed reply after an uncertain response.",
    source: "backend/src/main/java/dev/atlas/payments/PaymentService.java",
    test: `${paymentTests}#secondApplicationContextRecoversCommittedResponseAndHistory`,
    failureCase:
      "A caller that invents a new key after losing a response can create another authorization; HTTP success alone is insufficient evidence.",
    verification:
      "Run committed reply recovery and compare original/replayed bodies and effect count; a real dropped-response experiment is additional work.",
    terms: ["same key", "replay", "unknown"],
  },
  {
    area: "APIs",
    claim:
      "A stored canonical fingerprint binds an idempotency key to one payment intent and original response.",
    source: "backend/src/main/java/dev/atlas/payments/PaymentService.java",
    test: `${paymentTests}#replayReturnsOriginalSnapshotAndChangedPayloadConflicts`,
    failureCase:
      "Changing the amount under the same key must conflict; replay after later mutation must return the original reply.",
    verification:
      "Run the replay/conflict test; record status, response identity and durable effect count for both branches.",
    terms: ["advisory", "fingerprint", "transaction"],
  },
  {
    area: "Distributed systems",
    claim:
      "A consumer inbox suppresses duplicate effects when an outbox lease is retried after commit but before acknowledgment.",
    source: "backend/src/main/java/dev/atlas/eventing/DeliveryService.java",
    test: "backend/src/test/java/dev/atlas/eventing/DeliveryIntegrationTest.java#crashAfterCommitKeepsOneEffectAndSuppressesDuplicate",
    failureCase:
      "The first worker commits its effect but omits acknowledgment, so a later lease delivers the envelope again.",
    verification:
      "Run the commit-before-ack test; record one effect, eventual delivery and duplicate suppression. This is not power-loss testing.",
    terms: ["inbox", "lease", "duplicate"],
  },
  {
    area: "System design",
    claim:
      "Keeping payment and outbox state in one database permits a local atomic boundary with explicit availability tradeoffs.",
    source: "docs/DECISIONS/001-modular-monolith.md",
    test: "backend/src/test/java/dev/atlas/eventing/DeliveryIntegrationTest.java#outboxInsertFailureRollsBackAllPaymentEffects",
    failureCase:
      "Separating the ledger or event store changes the consistency boundary; failure between separate commits can split state.",
    verification:
      "Compare ADR assumptions with the outbox failure rollback test; describe additional protocols and operating costs of a split deployment.",
    terms: ["atomic", "operating", "boundary"],
  },
  {
    area: "Testing",
    claim:
      "Concurrent requests must preserve the remaining refundable balance across independent transactions.",
    source: paymentTests,
    test: `${paymentTests}#concurrentRefundsNeverExceedCapture`,
    failureCase:
      "Two refunds can each pass a sequential precheck but jointly exceed the captured amount without serialization.",
    verification:
      "Run the concurrent refund test; record accepted/rejected responses, refunded total and balanced ledger rather than only test count.",
    terms: ["concurrent", "remaining", "balance"],
  },
  {
    area: "Debugging",
    claim:
      "Correlated HTTP and JDBC spans can separate request work from database operation evidence.",
    source: "backend/src/main/java/dev/atlas/observability/Telemetry.java",
    test: `${engineeringTests}#actualHttpAndJdbcSpansShareTraceAndLogCorrelation`,
    failureCase:
      "Comparing unrelated traces or conflating connection acquisition and query time can assign the wrong cause.",
    verification:
      "Run trace correlation and inspect a baseline/affected investigation; identify trace IDs and measured operation names before inferring a cause.",
    terms: ["connection", "query", "baseline"],
  },
  {
    area: "Security",
    claim:
      "Approved read-only import rejects escaping paths and treats retrieved instructions and credentials as untrusted input.",
    source: "backend/src/main/java/dev/atlas/intelligence/RepositoryIndex.java",
    test: `${intelligenceTests}#requiresApprovalAndRejectsTraversalAbsoluteAndWindowsPaths`,
    failureCase:
      "An unapproved root or traversal path must be rejected; secret masking remains heuristic even when path tests pass.",
    verification:
      "Run the path/approval test and separately inspect redaction and injection tests; do not infer that a passing test proves all source is secret-free.",
    terms: ["read-only", "redact", "approval"],
  },
  {
    area: "Cloud infrastructure",
    claim:
      "One database and a finite connection pool bound availability; higher traffic requires measured capacity rather than scaling assumptions.",
    source: "compose.yaml",
    test: `${engineeringTests}#incidentRecipesMeasureBaselineAndAffectedWorkAndPersistEvidence`,
    failureCase:
      "Exhausted connections can delay or reject requests even when the API process is healthy; losing the single database removes the durable state boundary.",
    verification:
      "Run the isolated pool incident case and record acquisition timeout, recovery and measured workload. A small local run does not establish capacity at ten times the traffic; review the separate backup drill for recovery evidence.",
    terms: ["pool", "measure", "database"],
  },
  {
    area: "Observability",
    claim:
      "A reported percentile depends on the retained sample, observation window and workload and cannot alone establish a production SLO.",
    source:
      "backend/src/main/java/dev/atlas/observability/IncidentService.java",
    test: "backend/src/test/java/dev/atlas/observability/TelemetryTest.java#boundedSpanAndLatencyRetentionDoesNotLoseLifetimeCounters",
    failureCase:
      "A small warm or bounded sample can hide cold paths and discarded observations even if lifetime counters are retained.",
    verification:
      "Run retention checks and compare an investigation's baseline/affected samples; state sample sizes, window and workload before interpreting p95.",
    terms: ["sample", "window", "workload"],
  },
  {
    area: "AI-assisted engineering",
    claim:
      "A citation can identify allowed evidence while leaving the meaning and correctness of a model assertion unverified.",
    source:
      "backend/src/main/java/dev/atlas/intelligence/AssistantService.java",
    test: `${intelligenceTests}#providerOutageAndInvalidCitationsFallBackWithoutLosingEvidence`,
    failureCase:
      "An invalid citation or provider outage must preserve local evidence; a valid citation can still accompany a false interpretation.",
    verification:
      "Run outage/citation tests, inspect each cited excerpt and execute the proposed test separately; record uncertainty instead of treating citations as proof.",
    terms: ["source", "test", "uncertainty"],
  },
];

export type ReviewEvidence = {
  sourceReference: string;
  testReference: string;
  observation: string;
  inspected: boolean;
};
export const emptyReviewEvidence = (): ReviewEvidence => ({
  sourceReference: "",
  testReference: "",
  observation: "",
  inspected: false,
});
const caveats = [
  "Keyword coverage is deterministic text matching, not correctness or a skill grade.",
  "Reference matching checks the reviewed public allowlist, not whether a test ran or an observation is true.",
  "Observations and inspection are self-reported; human verification is not independently established.",
];
export function assessReview(
  criterion: ReviewCriterion,
  answer: string,
  evidence: ReviewEvidence,
) {
  const words = answer.toLowerCase().match(/[a-z0-9]+(?:-[a-z0-9]+)*/g) ?? [];
  const normalized = ` ${words.join(" ")} `;
  const sourceMatched = evidence.sourceReference === criterion.source;
  const testMatched = evidence.testReference === criterion.test;
  const observationProvided =
    evidence.observation.trim().length >= 30 &&
    evidence.observation.length <= 1200;
  const evidenceSupplied =
    sourceMatched &&
    testMatched &&
    observationProvided &&
    evidence.inspected === true;
  return {
    keywordCoverage: criterion.terms.map((term) => ({
      term,
      mentioned: normalized.includes(` ${term.toLowerCase()} `),
    })),
    sourceMatched,
    testMatched,
    observationProvided,
    inspectionSelfReported: evidence.inspected === true,
    evidenceSupplied,
    status: evidenceSupplied
      ? "Reference-matched self-reported evidence supplied"
      : "Evidence incomplete or references unmatched",
    humanVerification: "Not independently verified",
    caveats,
  };
}
export function reviewBody(
  criterion: ReviewCriterion,
  question: string,
  answer: string,
  evidence: ReviewEvidence,
) {
  if (
    !answer.trim() ||
    answer.length > 4000 ||
    evidence.sourceReference.length > 240 ||
    evidence.testReference.length > 240 ||
    evidence.observation.length > 1200
  )
    return null;
  const body = {
    question,
    answer,
    criterion,
    evidence,
    assessment: assessReview(criterion, answer, evidence),
  };
  return new TextEncoder().encode(JSON.stringify(body)).length <= 12000
    ? body
    : null;
}

export const adrFields = [
  "Context",
  "Decision",
  "Alternatives",
  "Tradeoffs",
  "Consequences",
  "Review date",
];
export function validReviewDate(value: unknown): boolean {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value))
    return false;
  const [year, month, day] = value.split("-").map(Number);
  if (year < 1 || month < 1 || month > 12 || day < 1) return false;
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  return (
    day <=
    [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
  );
}
export function completeAdr(body: Record<string, unknown>): boolean {
  return (
    adrFields.every(
      (field) =>
        Object.hasOwn(body, field) &&
        typeof body[field] === "string" &&
        (body[field] as string).trim().length > 0 &&
        (body[field] as string).length <= 1200,
    ) && validReviewDate(body["Review date"])
  );
}
