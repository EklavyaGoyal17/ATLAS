import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import {
  assessReview,
  completeAdr,
  emptyReviewEvidence,
  reviewBody,
  reviewCriteria,
  validReviewDate,
} from "../src/reviewEvidence.ts";

const api = reviewCriteria.find((item) => item.area === "APIs");
const supplied = () => ({
  sourceReference: api.source,
  testReference: api.test,
  observation:
    "The changed amount returned a conflict; the original reply replayed unchanged. This is my local observation, not production evidence.",
  inspected: true,
});

test("all fourteen criteria have existing exact source/test references, concrete failure cases and verification actions", async () => {
  assert.equal(reviewCriteria.length, 14);
  assert.equal(new Set(reviewCriteria.map((item) => item.area)).size, 14);
  for (const item of reviewCriteria) {
    for (const key of ["claim", "failureCase", "verification"])
      assert.ok(item[key].length > 50, `${item.area}: ${key}`);
    assert.ok(
      (await readFile(new URL(`../../${item.source}`, import.meta.url), "utf8"))
        .length > 0,
    );
    const [path, method] = item.test.split("#");
    const code = await readFile(
      new URL(`../../${path}`, import.meta.url),
      "utf8",
    );
    if (method)
      assert.ok(
        code.includes(`void ${method}(`),
        `${item.area}: missing test method`,
      );
    else assert.ok(path.endsWith(".test.mjs"));
    assert.equal(item.terms.length, 3);
  }
});

test("empty inputs and keyword-only answers never supply evidence or establish human verification", () => {
  for (const answer of [
    "",
    "advisory fingerprint transaction",
    "NOT advisory; fingerprint and transaction are false",
  ]) {
    const result = assessReview(api, answer, emptyReviewEvidence());
    assert.equal(result.evidenceSupplied, false);
    assert.equal(result.humanVerification, "Not independently verified");
  }
  assert.equal(reviewBody(api, "Question", "   ", emptyReviewEvidence()), null);
});

test("keyword matching uses word boundaries and stays explicitly separate from semantic truth", () => {
  assert.deepEqual(
    assessReview(
      api,
      "nonadvisory fingerprints transactional",
      emptyReviewEvidence(),
    ).keywordCoverage.map((item) => item.mentioned),
    [false, false, false],
  );
  assert.deepEqual(
    assessReview(
      api,
      "ADVISORY, fingerprint; transaction!",
      emptyReviewEvidence(),
    ).keywordCoverage.map((item) => item.mentioned),
    [true, true, true],
  );
  const networking = reviewCriteria.find((item) => item.area === "Networking");
  assert.equal(
    assessReview(
      networking,
      "Use the same\nkey for replay; outcome unknown.",
      emptyReviewEvidence(),
    ).keywordCoverage[0].mentioned,
    true,
  );
});

test("exact allowlisted references plus observation and self-attestation are required for evidence supplied", () => {
  const valid = supplied();
  const result = assessReview(api, "I am uncertain", valid);
  assert.equal(result.evidenceSupplied, true);
  assert.equal(result.inspectionSelfReported, true);
  assert.equal(result.humanVerification, "Not independently verified");
  assert.ok(
    result.caveats.some((text) => text.includes("not whether a test ran")),
  );
  for (const patch of [
    { sourceReference: "" },
    { testReference: "" },
    { observation: "I ran it" },
    { inspected: false },
  ]) {
    assert.equal(
      assessReview(api, "answer", { ...valid, ...patch }).evidenceSupplied,
      false,
    );
  }
});

test("URLs, traversal, suffixes, wrong methods and another topic's evidence cannot spoof the allowlist", () => {
  for (const sourceReference of [
    `https://github.com/${api.source}`,
    `../${api.source}`,
    `${api.source}.evil`,
    `${api.source} `,
    `<script>${api.source}</script>`,
  ]) {
    assert.equal(
      assessReview(api, "answer", { ...supplied(), sourceReference })
        .evidenceSupplied,
      false,
    );
  }
  for (const testReference of [
    api.test.split("#")[0],
    `${api.test}Extra`,
    reviewCriteria[0].test,
  ]) {
    assert.equal(
      assessReview(api, "answer", { ...supplied(), testReference })
        .evidenceSupplied,
      false,
    );
  }
});

test("review records preserve structured self-report, criterion and uncertainty without claiming test execution", () => {
  const evidence = supplied();
  const body = reviewBody(
    api,
    "What protects one intent?",
    "An advisory lock protects the fingerprint transaction.",
    evidence,
  );
  assert.ok(body);
  assert.deepEqual(body.evidence, evidence);
  assert.equal(body.criterion.failureCase, api.failureCase);
  assert.equal(body.assessment.evidenceSupplied, true);
  assert.equal(body.assessment.humanVerification, "Not independently verified");
  const incomplete = reviewBody(
    api,
    "Question",
    "unproven claim",
    emptyReviewEvidence(),
  );
  assert.equal(incomplete.assessment.evidenceSupplied, false);
});

test("field bounds and UTF-8 serialized size reject oversized records before HTTP submission", () => {
  assert.ok(
    reviewBody(api, "Q", "a".repeat(4000), {
      ...supplied(),
      observation: "b".repeat(1200),
    }),
  );
  assert.equal(reviewBody(api, "Q", "a".repeat(4001), supplied()), null);
  assert.equal(
    reviewBody(api, "Q", "answer", {
      ...supplied(),
      observation: "b".repeat(1201),
    }),
    null,
  );
  assert.equal(
    reviewBody(api, "Q", "answer", {
      ...supplied(),
      sourceReference: "a".repeat(241),
    }),
    null,
  );
  assert.equal(
    reviewBody(api, "Q", "answer", {
      ...supplied(),
      testReference: "a".repeat(241),
    }),
    null,
  );
  assert.equal(reviewBody(api, "Q", "漢".repeat(4000), supplied()), null);
});

test("review dates use strict four-digit proleptic Gregorian dates, not normalized JavaScript dates", () => {
  for (const value of [
    "0001-01-01",
    "9999-12-31",
    "2000-02-29",
    "2024-02-29",
    "2026-10-22",
  ])
    assert.equal(validReviewDate(value), true, value);
  for (const value of [
    "0000-01-01",
    "10000-01-01",
    "1900-02-29",
    "2025-02-29",
    "2026-04-31",
    "2026-13-01",
    "2026-01-00",
    "2026-1-01",
    " 2026-01-01",
    "2026-01-01T00:00:00Z",
    null,
    {},
    20261022,
  ])
    assert.equal(validReviewDate(value), false, String(value));
});

test("ADR writes require all six bounded nonblank strings while preserving extra legacy fields", () => {
  const body = {
    Context: "C",
    Decision: "D",
    Alternatives: "A",
    Tradeoffs: "T",
    Consequences: "C",
    "Review date": "2026-10-22",
    custom: { legacy: true },
  };
  assert.equal(completeAdr(body), true);
  for (const field of [
    "Context",
    "Decision",
    "Alternatives",
    "Tradeoffs",
    "Consequences",
    "Review date",
  ]) {
    const missing = { ...body };
    delete missing[field];
    assert.equal(completeAdr(missing), false);
    for (const value of ["", "  ", 1, false, {}, "x".repeat(1201)])
      assert.equal(completeAdr({ ...body, [field]: value }), false);
  }
  assert.equal(completeAdr({ ...body, Context: "x".repeat(1200) }), true);
  assert.equal(completeAdr({ ...body, "Review date": "2026-02-30" }), false);
  assert.equal(completeAdr(Object.create(body)), false);
});
