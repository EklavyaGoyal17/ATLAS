import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./api";
import { Notice } from "./ui";
import { download } from "./workspace";

type Declaration = {
  id: string;
  name: string;
  path: string;
  line: number;
  fanIn: number;
  fanOut: number;
};
type Relationship = { from: string; to: string; path: string; line: number };
type Report = {
  snapshotId: string;
  types: Declaration[];
  relationships: Relationship[];
  cycles: {
    members: string[];
    evidence: Relationship[];
    evidenceTruncated: boolean;
  }[];
  hotspots: {
    typeId: string;
    fanIn: number;
    fanOut: number;
    evidence: Relationship[];
    evidenceTruncated: boolean;
  }[];
  unresolvedFields: number;
  skippedFiles: number;
  truncated: boolean;
  limitations: string[];
};

export function ArchitectureReview({
  token,
  snapshotId,
  open,
}: {
  token: string;
  snapshotId: string;
  open: (path: string, line: number) => void;
}) {
  const cache = useQueryClient();
  const [search, setSearch] = useState("");
  const query = useQuery({
    queryKey: ["architecture-review", snapshotId],
    queryFn: () => api<Report>(token, "/repository/architecture"),
    staleTime: Infinity,
    retry: false,
  });
  const report = query.data?.snapshotId === snapshotId ? query.data : null;
  const types = new Map(report?.types.map((t) => [t.id, t]));
  const name = (id: string) => types.get(id)?.name ?? id;
  const references = (edges: Relationship[]) =>
    edges.map((edge, i) => (
      <p key={i}>
        <button
          className="text-button"
          onClick={() => open(edge.path, edge.line)}
        >
          {name(edge.from)} → {name(edge.to)} · {edge.path}:{edge.line}
        </button>
      </p>
    ));
  return (
    <section
      className="panel padded architecture-review"
      aria-labelledby="architecture-review-title"
    >
      <h2 id="architecture-review-title">Architecture review</h2>
      <p>
        Review declared dependency cycles and concentrated coupling in the
        approved snapshot. These are static review prompts; they do not
        establish runtime calls or defects.
      </p>
      <Notice error={query.error} />
      {query.isError && (
        <button
          className="secondary"
          disabled={query.isFetching}
          onClick={() => void query.refetch()}
        >
          Retry architecture analysis
        </button>
      )}
      {query.isPending && (
        <p role="status">Analyzing approved Java declarations…</p>
      )}
      {query.data && !report && (
        <p className="callout">
          The repository changed while analysis ran.{" "}
          <button
            className="text-button"
            onClick={() =>
              void cache.invalidateQueries({ queryKey: ["repository"] })
            }
          >
            Refresh repository snapshot
          </button>
        </p>
      )}
      {report && (
        <>
          <p className="architecture-summary" role="status">
            {report.types.length} resolved types · {report.relationships.length}{" "}
            internal declaration references · {report.cycles.length} cycle
            groups · {report.hotspots.length} coupling candidates
          </p>
          <p className="muted">
            {report.unresolvedFields} unresolved/external field types ·{" "}
            {report.skippedFiles} unparsed/withheld Java files. Fan-in/out count
            distinct types, not repeated fields.
          </p>
          {report.truncated && (
            <p className="callout">
              Analysis reached a report budget. Findings cover a partial graph;
              review the limits below.
            </p>
          )}
          <button
            className="secondary"
            onClick={() =>
              download(
                "atlas-architecture-review.json",
                JSON.stringify(report, null, 2),
              )
            }
          >
            Export architecture review
          </button>
          <h3>Declared dependency cycles</h3>
          {!report.cycles.length && (
            <p>
              No multi-type cycles were found in the resolved subset. This does
              not establish an acyclic repository.
            </p>
          )}
          {report.cycles.map((cycle, i) => (
            <details key={i} className="architecture-cycle">
              <summary>
                Cycle group {i + 1} · {cycle.members.length} mutually reachable
                types
              </summary>
              <p>
                Members form a strongly connected group; their listed order is
                not an execution path.
              </p>
              {cycle.members.slice(0, 20).map((id) => {
                const type = types.get(id)!;
                return (
                  <p key={id}>
                    <button
                      className="text-button"
                      onClick={() => open(type.path, type.line)}
                    >
                      {type.name} · {type.path}:{type.line}
                    </button>
                  </p>
                );
              })}
              {cycle.members.length > 20 && (
                <p>
                  First 20 members shown; export includes every bounded member.
                </p>
              )}
              <h4>Field declaration evidence</h4>
              {references(cycle.evidence)}
              {cycle.evidenceTruncated && (
                <p>
                  First 40 references shown; export includes the bounded
                  relationship graph.
                </p>
              )}
            </details>
          ))}
          <h3>Coupling review candidates</h3>
          <p>
            Threshold: five distinct incoming or outgoing types. Shared domain
            types can legitimately have many dependants.
          </p>
          {!report.hotspots.length && (
            <p>No resolved type reached the review threshold.</p>
          )}
          {report.hotspots.map((candidate) => (
            <details key={candidate.typeId}>
              <summary>
                {name(candidate.typeId)} · fan-in {candidate.fanIn} · fan-out{" "}
                {candidate.fanOut}
              </summary>
              {references(candidate.evidence)}
              {candidate.evidenceTruncated && (
                <p>
                  First 20 references shown; export includes the bounded
                  relationship graph.
                </p>
              )}
            </details>
          ))}
          <label>
            Find analyzed type
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Qualified class or record name"
            />
          </label>
          <div className="symbol-list">
            {report.types
              .filter((t) =>
                t.name.toLowerCase().includes(search.toLowerCase()),
              )
              .slice(0, 50)
              .map((type) => (
                <button
                  key={type.id}
                  onClick={() => open(type.path, type.line)}
                >
                  <strong>{type.name}</strong>
                  <span>
                    {type.path}:{type.line} · fan-in {type.fanIn} · fan-out{" "}
                    {type.fanOut}
                  </span>
                </button>
              ))}
          </div>
          <p className="muted">
            First 50 matching declarations shown. Type names include Java
            packages and enclosing types.
          </p>
          <details>
            <summary>Analysis scope and limitations</summary>
            {report.limitations.map((limit) => (
              <p key={limit}>{limit}</p>
            ))}
          </details>
        </>
      )}
    </section>
  );
}
