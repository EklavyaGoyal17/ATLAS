import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Activity, Clock3, Database } from "lucide-react";
import { api, timestamp } from "./api";
import { Notice, Stat } from "./ui";
import { useDocuments, download } from "./workspace";
import { EndpointMetrics, type HttpEndpointMetrics } from "./EndpointMetrics";
import { IncidentBrief } from "./IncidentBrief";
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
type Evidence = {
  id: string;
  available: boolean;
  investigation: Incident | null;
  spans: Span[];
  truncated: boolean;
  scope: string;
};
type Metrics = HttpEndpointMetrics & {
  since: string;
  scope: string;
  p95Ms: number;
  requestsPerSecond: number;
  errorRate: number;
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
  const [archivePage, setArchivePage] = useState(0);
  const docs = useDocuments(token, archivePage, "INCIDENT");
  const [scenario, setScenario] = useState("SLOW_QUERY");
  const [incident, setIncident] = useState<Incident | null>(null);
  const [trace, setTrace] = useState("");
  const [hypothesis, setHypothesis] = useState("");
  const [resolution, setResolution] = useState("");
  const [saved, setSaved] = useState(false);
  const [source, setSource] = useState<"live" | "saved">("live");
  const evidence = useQuery({
    queryKey: ["incident-evidence", incident?.id],
    queryFn: () =>
      api<Evidence>(token, `/observability/incidents/${incident!.id}/evidence`),
    enabled: !!incident,
    retry: false,
  });
  const currentIncident = evidence.data?.investigation ?? incident;
  const metrics = useQuery({
    queryKey: ["metrics"],
    queryFn: () => api<Metrics>(token, "/observability/metrics"),
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
      setSource("saved");
      setTrace(r.affected[0].traceId);
      setSaved(false);
      setHypothesis("");
      setResolution("");
      setArchivePage(0);
      void cache.invalidateQueries();
    },
  });
  const visible = (
    source === "saved" ? (evidence.data?.spans ?? []) : (spans.data ?? [])
  )
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
          docs.save.error ||
          docs.query.error ||
          evidence.error
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
          caption="Lifetime requests / observation time"
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
        . Live telemetry resets on restart. Saved investigations and their
        bounded span evidence persist in PostgreSQL.
      </p>
      {metrics.data && (
        <>
          <p className="muted">
            HTTP counter lifetime starts {timestamp(metrics.data.since)}.
          </p>
          <EndpointMetrics {...metrics.data} />
        </>
      )}
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
        <label>
          Saved investigation
          <select
            value={
              docs.query.data?.some((d) => d.id === incident?.id)
                ? incident?.id
                : ""
            }
            onChange={(e) => {
              const record = docs.query.data?.find(
                (d) => d.id === e.target.value,
              );
              if (!record) return;
              setIncident(record.body as unknown as Incident);
              setSource("saved");
              setTrace("");
              setSaved(false);
              setHypothesis("");
              setResolution("");
            }}
          >
            <option value="">Choose an investigation</option>
            {docs.query.data?.map((d) => (
              <option key={d.id} value={d.id}>
                {d.title.replace("Investigation: ", "")} ·{" "}
                {timestamp(d.updatedAt)} · {d.id.slice(0, 8)}
              </option>
            ))}
          </select>
        </label>
        <div className="button-row">
          <button
            className="secondary"
            disabled={archivePage === 0 || docs.query.isFetching}
            onClick={() => setArchivePage((p) => p - 1)}
          >
            Previous investigations
          </button>
          <span>Investigation page {archivePage + 1} of at most 5</span>
          <button
            className="secondary"
            disabled={
              archivePage === 4 ||
              docs.query.data?.length !== 200 ||
              docs.query.isFetching
            }
            onClick={() => setArchivePage((p) => p + 1)}
          >
            Next investigations
          </button>
        </div>
        {currentIncident && (
          <div className="module-stack">
            <p className="callout">{currentIncident.scope}</p>
            <h3>{currentIncident.scenario}</h3>
            <div className="two-columns">
              {(["baseline", "affected"] as const).map((kind) => (
                <div key={kind}>
                  <h3>{kind}</h3>
                  {currentIncident[kind].map((s, i) => (
                    <button
                      key={i}
                      className="trace-sample"
                      onClick={() => {
                        setSource("saved");
                        setTrace(s.traceId);
                      }}
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
            <p>{currentIncident.hypothesis}</p>
            <p>{currentIncident.validation}</p>
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
                    title: `Postmortem: ${currentIncident.scenario}`,
                    version: 0,
                    body: {
                      incidentId: currentIncident.id,
                      hypothesis,
                      resolution,
                      evidence: currentIncident.affected,
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
              disabled={evidence.isPending || evidence.isError}
              onClick={() =>
                download(
                  "atlas-incident.json",
                  JSON.stringify(
                    { ...evidence.data, investigation: currentIncident },
                    null,
                    2,
                  ),
                )
              }
            >
              Export investigation
            </button>
          </div>
        )}
      </section>
      {currentIncident && (
        <IncidentBrief
          key={currentIncident.id}
          token={token}
          id={currentIncident.id}
          onTrace={(traceId) => {
            setSource("saved");
            setTrace(traceId);
          }}
        />
      )}
      <section className="panel padded">
        <h2>Trace explorer</h2>
        <div className="button-row">
          <button
            className="secondary"
            onClick={() => {
              setSource("live");
              setTrace("");
            }}
          >
            Show live spans
          </button>
          <button
            className="secondary"
            disabled={!incident}
            onClick={() => {
              setSource("saved");
              setTrace("");
            }}
          >
            Show saved evidence
          </button>
        </div>
        <p className="trace-source" aria-live="polite">
          {source === "saved"
            ? "Saved investigation evidence"
            : "Live JVM telemetry"}
        </p>
        {source === "saved" && (
          <p className="callout">
            {evidence.isPending
              ? "Loading saved evidence…"
              : evidence.data?.scope}
            {evidence.data?.truncated &&
              " The saved evidence was truncated to its retention budget."}
          </p>
        )}
        <label>
          Trace ID filter
          <input
            value={trace}
            onChange={(e) => setTrace(e.target.value)}
            placeholder="All retained traces"
          />
        </label>
        <button className="secondary" onClick={() => setTrace("")}>
          Show all {source === "saved" ? "saved" : "live"} spans
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
                <svg
                  className="duration-bar"
                  viewBox="0 0 100 7"
                  aria-hidden="true"
                >
                  <rect
                    width={Math.min(100, Math.max(2, s.durationMs))}
                    height="7"
                    fill="#62ccb1"
                  />
                </svg>
                {s.durationMs.toFixed(2)} ms
              </div>
            </div>
          ))}
          {!visible.length && (
            <p>
              No spans match this source and filter. Older investigations may
              predate saved span evidence; expired spans cannot be
              reconstructed.
            </p>
          )}
        </div>
        <p className="muted">
          At most 100 matching spans are shown; exports include all saved spans.
          Span names use static route families. SQL text and request bodies are
          excluded. Correlation IDs match structured API logs available through{" "}
          <code>docker compose logs api</code>.
        </p>
      </section>
    </div>
  );
}
