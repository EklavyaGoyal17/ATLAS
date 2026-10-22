import { useQuery } from "@tanstack/react-query";
import { api } from "./api";
import { Notice } from "./ui";

type Measurements = {
  samples: number;
  measuredSamples: number;
  failedSamples: number;
  medianMs: number | null;
};
type Citation = { traceId: string; spanId: string | null; label: string };
type Brief = {
  id: string;
  mode: string;
  evidenceAvailable: boolean;
  scenario: string | null;
  baseline: Measurements;
  affected: Measurements;
  affectedMinusBaselineMs: number | null;
  retainedSpans: number;
  errorSpans: number;
  slowestChild: {
    traceId: string;
    spanId: string;
    parentId: string;
    name: string;
    durationMs: number;
    status: string;
  } | null;
  findings: { kind: string; text: string; citations: Citation[] }[];
  limitations: string[];
};
const duration = (value: number | null) =>
  value === null ? "Unavailable" : `${value.toFixed(2)} ms`;

export function IncidentBrief({
  token,
  id,
  onTrace,
}: {
  token: string;
  id: string;
  onTrace: (traceId: string) => void;
}) {
  const summary = useQuery({
    queryKey: ["incident-brief", token, id],
    queryFn: () =>
      api<Brief>(
        token,
        `/assistant/incidents/${encodeURIComponent(id)}/summary`,
      ),
    enabled: !!id,
    retry: false,
    staleTime: 0,
  });
  const data =
    summary.data?.id === id && !summary.isError ? summary.data : undefined;
  return (
    <section
      className="panel padded"
      aria-label="Deterministic incident summary"
    >
      <h3>Deterministic incident summary</h3>
      <p className="muted">
        Calculated from this saved investigation. No AI model or private journal
        context is used.
      </p>
      <Notice error={summary.error} />
      {summary.isPending && (
        <p role="status">Loading saved incident summary…</p>
      )}
      {data && (
        <div className="module-stack">
          {!data.evidenceAvailable && (
            <p className="callout">
              Saved evidence is unavailable for this investigation.
            </p>
          )}
          {data.evidenceAvailable && (
            <>
              <p>
                {data.scenario} · {data.retainedSpans} usable retained spans ·{" "}
                {data.errorSpans} error spans
              </p>
              <div className="two-columns">
                {(["baseline", "affected"] as const).map((kind) => (
                  <div key={kind}>
                    <h4>
                      {kind === "baseline"
                        ? "Baseline measurements"
                        : "Affected measurements"}
                    </h4>
                    <p>
                      {data[kind].samples} samples ·{" "}
                      {data[kind].measuredSamples} usable durations ·{" "}
                      {data[kind].failedSamples} failed samples
                    </p>
                    <p>
                      Median: <strong>{duration(data[kind].medianMs)}</strong>
                    </p>
                  </div>
                ))}
              </div>
              <p>
                Affected minus baseline median:{" "}
                <strong>{duration(data.affectedMinusBaselineMs)}</strong>
              </p>
              {data.slowestChild && (
                <p>
                  Slowest retained child:{" "}
                  <strong>{data.slowestChild.name}</strong> ·{" "}
                  {duration(data.slowestChild.durationMs)} ·{" "}
                  {data.slowestChild.status}
                </p>
              )}
            </>
          )}
          {data.findings.map((finding) => (
            <div key={finding.kind}>
              <p>{finding.text}</p>
              <div className="button-row">
                {finding.citations.map((citation) => (
                  <button
                    type="button"
                    className="secondary"
                    key={`${citation.traceId}:${citation.spanId ?? "sample"}`}
                    onClick={() => onTrace(citation.traceId)}
                  >
                    {citation.label} · trace {citation.traceId.slice(0, 12)}
                    {citation.spanId ? ` · span ${citation.spanId}` : ""}
                  </button>
                ))}
              </div>
            </div>
          ))}
          <h4>Evidence limits</h4>
          <ul>
            {data.limitations.map((limit) => (
              <li key={limit}>{limit}</li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
