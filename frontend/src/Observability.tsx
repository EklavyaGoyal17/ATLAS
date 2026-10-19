import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Activity, Clock3, Database } from "lucide-react";
import { api, timestamp } from "./api";
import { Notice, Stat } from "./ui";
import { useDocuments, download } from "./workspace";
type Span = {
  traceId: string;
  spanId: string;
  parentId: string;
  name: string;
  durationMs: number;
  status: string;
  correlationId: string;
  startedAt: string;
};
type Sample = { traceId: string; durationMs: number; outcome: string };
type Incident = {
  id: string;
  scenario: string;
  baseline: Sample[];
  affected: Sample[];
  hypothesis: string;
  validation: string;
  scope: string;
};
const scenarios = [
  "SLOW_QUERY",
  "LOCK_CONTENTION",
  "DOWNSTREAM_TIMEOUT",
  "CONNECTION_EXHAUSTION",
  "EXCESSIVE_RETRIES",
  "CACHE_MISSES",
  "QUEUE_BACKLOG",
  "MISCONFIGURED_TIMEOUT",
];
export function Observability({ token }: { token: string }) {
  const cache = useQueryClient();
  const docs = useDocuments(token, 0, "INCIDENT");
  const [scenario, setScenario] = useState("SLOW_QUERY");
  const [incident, setIncident] = useState<Incident | null>(null);
  const [trace, setTrace] = useState("");
  const [hypothesis, setHypothesis] = useState("");
  const [resolution, setResolution] = useState("");
  const [saved, setSaved] = useState(false);
  const metrics = useQuery({
    queryKey: ["metrics"],
    queryFn: () =>
      api<Record<string, string | number>>(token, "/observability/metrics"),
    refetchInterval: 3000,
  });
  const spans = useQuery({
    queryKey: ["traces"],
    queryFn: () => api<Span[]>(token, "/observability/traces"),
    refetchInterval: 3000,
  });
  const worker = useQuery({
    queryKey: ["worker"],
    queryFn: () =>
      api<{ pending: number; retry: number; processing: number; dead: number }>(
        token,
        "/worker",
      ),
    refetchInterval: 3000,
  });
  const inject = useMutation({
    mutationFn: () =>
      api<Incident>(token, "/observability/incidents", {
        method: "POST",
        body: { scenario },
      }),
    onSuccess: (r) => {
      setIncident(r);
      setTrace(r.affected[0].traceId);
      setSaved(false);
      void cache.invalidateQueries();
    },
  });
  const visible = (spans.data ?? [])
    .filter((s) => !trace || s.traceId === trace)
    .slice(0, 100);
  return (
    <div className="module-stack">
      <Notice
        error={
          metrics.error ||
          spans.error ||
          worker.error ||
          inject.error ||
          docs.save.error
        }
      />
      <div className="stats">
        <Stat
          title="HTTP p95"
          value={
            metrics.data ? `${Number(metrics.data.p95Ms).toFixed(1)} ms` : "—"
          }
          caption="Measured retained HTTP spans"
          icon={Clock3}
        />
        <Stat
          title="Requests / second"
          value={
            metrics.data
              ? Number(metrics.data.requestsPerSecond).toFixed(2)
              : "—"
          }
          caption="Retained requests / observation time"
          icon={Activity}
        />
        <Stat
          title="Queue awaiting delivery"
          value={
            worker.data
              ? worker.data.pending + worker.data.retry + worker.data.processing
              : "—"
          }
          caption={
            worker.data
              ? `${worker.data.retry} retries · ${worker.data.dead} dead letters`
              : "Loading worker state"
          }
          icon={Database}
        />
      </div>
      <p className="muted">
        {metrics.data?.scope} HTTP error rate:{" "}
        {metrics.data
          ? `${(Number(metrics.data.errorRate) * 100).toFixed(1)}%`
          : "—"}
        . Telemetry stays in this JVM and resets on restart; saved
        investigations persist.
      </p>
      <section className="panel padded">
        <div className="panel-heading">
          <div>
            <h2>Investigate a controlled incident</h2>
            <p>
              Three measured baseline samples, then three affected samples.
              Payment state is isolated.
            </p>
          </div>
        </div>
        <div className="form-row">
          <label>
            Incident scenario
            <select
              value={scenario}
              onChange={(e) => setScenario(e.target.value)}
            >
              {scenarios.map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
          </label>
          <button
            className="primary"
            disabled={inject.isPending}
            onClick={() => inject.mutate()}
          >
            {inject.isPending ? "Measuring…" : "Run investigation"}
          </button>
        </div>
        <div className="button-row">
          {docs.query.data
            ?.filter((d) => d.kind === "INCIDENT")
            .slice(0, 8)
            .map((d) => (
              <button
                className="secondary"
                key={d.id}
                onClick={() => {
                  setIncident(d.body as unknown as Incident);
                  setTrace("");
                  setSaved(false);
                }}
              >
                {d.title.replace("Investigation: ", "")}
              </button>
            ))}
        </div>
        {incident && (
          <div className="module-stack">
            <p className="callout">{incident.scope}</p>
            <h3>{incident.scenario}</h3>
            <div className="two-columns">
              {(["baseline", "affected"] as const).map((kind) => (
                <div key={kind}>
                  <h3>{kind}</h3>
                  {incident[kind].map((s, i) => (
                    <button
                      key={i}
                      className="trace-sample"
                      onClick={() => setTrace(s.traceId)}
                    >
                      <span>
                        Sample {i + 1} · {s.outcome}
                      </span>
                      <strong>{s.durationMs.toFixed(2)} ms</strong>
                      <small>Open trace {s.traceId.slice(0, 12)}</small>
                    </button>
                  ))}
                </div>
              ))}
            </div>
            <h3>Injection evidence</h3>
            <p>{incident.hypothesis}</p>
            <p>{incident.validation}</p>
            <label>
              Your root-cause hypothesis
              <textarea
                value={hypothesis}
                maxLength={2000}
                onChange={(e) => setHypothesis(e.target.value)}
              />
            </label>
            <label>
              Validation and corrective action
              <textarea
                value={resolution}
                maxLength={2000}
                onChange={(e) => setResolution(e.target.value)}
              />
            </label>
            <button
              className="primary"
              disabled={!hypothesis || !resolution || docs.save.isPending}
              onClick={() =>
                docs.save.mutate(
                  {
                    id: crypto.randomUUID(),
                    kind: "POSTMORTEM",
                    title: `Postmortem: ${incident.scenario}`,
                    version: 0,
                    body: {
                      incidentId: incident.id,
                      hypothesis,
                      resolution,
                      evidence: incident.affected,
                    },
                  },
                  { onSuccess: () => setSaved(true) },
                )
              }
            >
              Save postmortem
            </button>
            {saved && (
              <p role="status">Postmortem saved to Engineering growth.</p>
            )}
            <button
              className="secondary"
              onClick={() =>
                download(
                  "atlas-incident.json",
                  JSON.stringify(incident, null, 2),
                )
              }
            >
              Export investigation
            </button>
          </div>
        )}
      </section>
      <section className="panel padded">
        <h2>Trace explorer</h2>
        <label>
          Trace ID filter
          <input
            value={trace}
            onChange={(e) => setTrace(e.target.value)}
            placeholder="All retained traces"
          />
        </label>
        <button className="secondary" onClick={() => setTrace("")}>
          Show all spans
        </button>
        <div className="trace-list">
          {visible.map((s) => (
            <div className="span-row" key={s.spanId}>
              <button onClick={() => setTrace(s.traceId)}>
                <strong>{s.name}</strong>
                <small>
                  {timestamp(s.startedAt)} · {s.status}
                </small>
                <code>{s.traceId}</code>
                <small>
                  Span {s.spanId} · parent {s.parentId} · log correlation{" "}
                  {s.correlationId}
                </small>
              </button>
              <div>
                <svg className="duration-bar" viewBox="0 0 100 7" aria-hidden="true"><rect width={Math.min(100,Math.max(2,s.durationMs))} height="7" fill="#62ccb1"/></svg>
                {s.durationMs.toFixed(2)} ms
              </div>
            </div>
          ))}
          {!visible.length && (
            <p>
              No retained spans match. Saved investigations retain sample
              evidence after a JVM restart.
            </p>
          )}
        </div>
        <p className="muted">
          Span names use static route families. SQL text and request bodies are
          excluded. Correlation IDs match structured API logs available through{" "}
          <code>docker compose logs api</code>.
        </p>
      </section>
    </div>
  );
}
