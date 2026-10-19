import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api } from "./api";
import { Notice } from "./ui";
const recipes = [
  "SAGA_COMPENSATION",
  "CIRCUIT_BREAKER",
  "RATE_LIMIT",
  "BULKHEAD",
  "CACHE_INVALIDATION",
  "OPTIMISTIC_LOCK",
  "PESSIMISTIC_LOCK",
  "SYNC_ASYNC",
  "LOST_RESPONSE",
  "LEDGER_REPAIR",
  "SLO_BUDGET",
  "TIMEOUT_POLICY",
];
type Result = {
  scenario: string;
  seed: number;
  before: Record<string, number>;
  after: Record<string, number>;
  timeline: { tick: number; action: string; evidence: string }[];
  invariant: boolean;
  explanation: string;
  scope: string;
};
export function ModelLab({ token }: { token: string }) {
  const [scenario, setScenario] = useState(recipes[0]);
  const [seed, setSeed] = useState(42);
  const run = useMutation({
    mutationFn: () =>
      api<Result>(token, "/simulations", {
        method: "POST",
        body: { scenario, seed },
      }),
  });
  return (
    <section className="panel padded">
      <h2>Distributed systems models</h2>
      <p>
        Explore the consistency and resource policies behind the durable worker.
        These isolated discrete-event models operate on synthetic state and
        logical time.
      </p>
      <Notice error={run.error} />
      <div className="form-row">
        <label>
          Model scenario
          <select
            value={scenario}
            onChange={(e) => setScenario(e.target.value)}
          >
            {recipes.map((r) => (
              <option key={r}>{r}</option>
            ))}
          </select>
        </label>
        <label>
          Reproducible seed
          <input
            type="number"
            min="0"
            max="1000000"
            value={seed}
            onChange={(e) =>
              setSeed(Math.max(0, Math.min(1000000, Number(e.target.value))))
            }
          />
        </label>
        <button
          className="primary"
          disabled={run.isPending}
          onClick={() => run.mutate()}
        >
          Run logical experiment
        </button>
      </div>
      {run.data && (
        <div className="module-stack">
          <p className="callout">{run.data.scope}</p>
          <div className="two-columns">
            <div>
              <h3>Before</h3>
              <pre>{JSON.stringify(run.data.before, null, 2)}</pre>
            </div>
            <div>
              <h3>After</h3>
              <pre>{JSON.stringify(run.data.after, null, 2)}</pre>
            </div>
          </div>
          <p role="status">
            Final stated invariant:{" "}
            <strong>{run.data.invariant ? "holds" : "violated"}</strong>
          </p>
          <div>
            {run.data.timeline.map((e, i) => (
              <div className="edge-row" key={i}>
                <code>
                  t={e.tick} · {e.action}
                </code>
                <span>{e.evidence}</span>
              </div>
            ))}
          </div>
          <p>{run.data.explanation}</p>
        </div>
      )}
    </section>
  );
}
