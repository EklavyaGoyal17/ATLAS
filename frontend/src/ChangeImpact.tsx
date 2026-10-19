import { useEffect, useId, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
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
type Impact = {
  type: Declaration;
  distance: number;
  changedTypeId: string;
  path: Relationship[];
  pathTruncated: boolean;
};
type Report = {
  snapshotId: string;
  selectedPaths: string[];
  seeds: Declaration[];
  affected: Impact[];
  totalAffected: number;
  unrepresentedPaths: string[];
  unresolvedFields: number;
  skippedFiles: number;
  graphTruncated: boolean;
  resultsTruncated: boolean;
  limitations: string[];
};

const samePaths = (left: string[], right: string[]) => {
  const expected = [...right].sort();
  return (
    left.length === right.length &&
    [...left].sort().every((path, i) => path === expected[i])
  );
};

export function ChangeImpact({
  token,
  snapshotId,
  files,
  open,
}: {
  token: string;
  snapshotId: string;
  files: { path: string }[];
  open: (path: string, line: number) => void;
}) {
  const titleId = useId();
  const cache = useQueryClient();
  const [filter, setFilter] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [stale, setStale] = useState(false);
  const [pending, setPending] = useState(false);
  const intent = useRef(0);
  const request = useRef(0);
  useEffect(
    () => () => {
      intent.current++;
      request.current++;
    },
    [],
  );
  function toggle(path: string) {
    if (!selected.includes(path) && selected.length === 20) return;
    intent.current++;
    setSelected((current) =>
      current.includes(path)
        ? current.filter((item) => item !== path)
        : [...current, path].sort(),
    );
    setReport(null);
    setError(null);
    setStale(false);
  }
  async function analyze() {
    if (!selected.length || pending) return;
    const version = intent.current;
    const requestId = ++request.current;
    const paths = [...selected];
    setPending(true);
    setReport(null);
    setError(null);
    setStale(false);
    try {
      const result = await api<Report>(token, "/repository/impact", {
        method: "POST",
        body: { snapshotId, paths },
      });
      if (version !== intent.current) return;
      if (
        result.snapshotId !== snapshotId ||
        !samePaths(result.selectedPaths, paths)
      ) {
        setStale(true);
        return;
      }
      setReport(result);
    } catch (failure) {
      if (version === intent.current) setError(failure);
    } finally {
      if (requestId === request.current) setPending(false);
    }
  }
  const current =
    report?.snapshotId === snapshotId &&
    samePaths(report.selectedPaths, selected)
      ? report
      : null;
  const names = new Map(
    current
      ? [...current.seeds, ...current.affected.map((item) => item.type)].map(
          (type) => [type.id, type.name],
        )
      : [],
  );
  const matching = files.filter((file) =>
    file.path.toLowerCase().includes(filter.toLowerCase()),
  );
  return (
    <section className="panel padded change-impact" aria-labelledby={titleId}>
      <h2 id={titleId}>Change impact explorer</h2>
      <p>
        Select files you plan to change in the approved snapshot. Follow
        resolved Java field dependencies backwards to review their dependants.
        Distance 1 is a direct dependant; larger distances follow transitive
        dependencies. This does not select tests, establish runtime blast
        radius, or prove a defect.
      </p>
      <label>
        Find impact file
        <input
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
          placeholder="PaymentStore.java or an indexed path"
        />
      </label>
      <p className="muted">
        {selected.length}/20 files selected · {matching.length} matching indexed
        files. Filtering keeps your selections.
      </p>
      <div className="impact-files">
        {matching.map((file) => (
          <label className="check-label" key={file.path}>
            <input
              type="checkbox"
              checked={selected.includes(file.path)}
              disabled={selected.length >= 20 && !selected.includes(file.path)}
              onChange={() => toggle(file.path)}
            />
            <span>{file.path}</span>
          </label>
        ))}
        {!matching.length && <p>No indexed files match this filter.</p>}
      </div>
      {!!selected.length && (
        <details>
          <summary>Selected files ({selected.length})</summary>
          {selected.map((path) => (
            <p key={path}>
              <button className="text-button" onClick={() => toggle(path)}>
                Remove {path}
              </button>
            </p>
          ))}
        </details>
      )}
      <div className="button-row">
        <button
          className="primary"
          disabled={!selected.length || pending}
          onClick={() => void analyze()}
        >
          Analyze selected files
        </button>
        {current && (
          <button
            className="secondary"
            onClick={() =>
              download(
                "atlas-change-impact.json",
                JSON.stringify(current, null, 2),
              )
            }
          >
            Export change impact
          </button>
        )}
      </div>
      {pending && <p role="status">Analyzing selected files…</p>}
      <Notice error={error} />
      {(error != null || stale) && (
        <p className="callout">
          {stale
            ? "The response no longer matches this snapshot and file selection. "
            : "Retry the analysis, or refresh if the imported repository changed. "}
          <button
            className="text-button"
            onClick={() => {
              intent.current++;
              setReport(null);
              void cache.invalidateQueries({ queryKey: ["repository"] });
              void cache.invalidateQueries({ queryKey: ["repo-config"] });
            }}
          >
            Refresh repository snapshot
          </button>
        </p>
      )}
      {current && (
        <>
          <p role="status">
            {current.seeds.length} changed declarations ·{" "}
            {current.totalAffected} reachable dependant types ·{" "}
            {current.affected.length} displayed
          </p>
          <p className="muted">
            {current.unresolvedFields} unresolved/external field types ·{" "}
            {current.skippedFiles} unparsed/withheld Java files. Results cover
            only the resolved static field graph.
          </p>
          {current.graphTruncated && (
            <p className="callout">
              The architecture analysis reached a limit. This flag can describe
              graph bounds or bounded architecture findings; review the scope
              below before interpreting the impact results.
            </p>
          )}
          {current.totalAffected > current.affected.length && (
            <p className="callout">
              First 200 dependant types returned. Additional reachable types are
              counted but omitted from the display and export.
            </p>
          )}
          {current.resultsTruncated && (
            <p className="callout">
              The returned impact evidence is bounded. Review individual path
              notices for omitted edges; each path includes at most 20 edges.
            </p>
          )}
          <h3>Changed declarations</h3>
          {current.seeds.map((type) => (
            <p key={type.id}>
              <button
                className="text-button"
                onClick={() => open(type.path, type.line)}
              >
                {type.name} · {type.path}:{type.line}
              </button>
            </p>
          ))}
          {!current.seeds.length && (
            <p>
              No selected files contain a declaration in the resolved graph.
            </p>
          )}
          {!!current.unrepresentedPaths.length && (
            <details open>
              <summary>
                Selected files outside the resolved graph (
                {current.unrepresentedPaths.length})
              </summary>
              <p>
                No impact result is available for these files. They may use
                another language, lack a supported declaration, or have been
                skipped by parsing or graph limits.
              </p>
              {current.unrepresentedPaths.map((path) => (
                <p key={path}>
                  <button className="text-button" onClick={() => open(path, 1)}>
                    {path}
                  </button>
                </p>
              ))}
            </details>
          )}
          <h3>Dependants to review</h3>
          {!current.affected.length && (
            <p>
              No dependant types were found in the resolved subset. This does
              not establish that the change has no impact.
            </p>
          )}
          {current.affected.map((item) => (
            <details key={item.type.id}>
              <summary>
                {item.type.name} ·{" "}
                {item.distance === 1
                  ? "direct dependant"
                  : `transitive dependant (distance ${item.distance})`}
              </summary>
              <p>
                <button
                  className="text-button"
                  onClick={() => open(item.type.path, item.type.line)}
                >
                  Open dependant declaration · {item.type.path}:{item.type.line}
                </button>
              </p>
              <p>
                One shortest dependency path from this dependant toward changed
                declaration{" "}
                {names.get(item.changedTypeId) ?? item.changedTypeId}. Each link
                opens the field declaration that supports that edge.
              </p>
              <ol>
                {item.path.map((edge, i) => (
                  <li key={i}>
                    <button
                      className="text-button"
                      onClick={() => open(edge.path, edge.line)}
                    >
                      {names.get(edge.from) ?? edge.from} →{" "}
                      {names.get(edge.to) ?? edge.to} · {edge.path}:{edge.line}
                    </button>
                  </li>
                ))}
              </ol>
              {item.pathTruncated && (
                <p className="callout">
                  First 20 dependency edges returned; the remaining path to the
                  changed declaration is omitted from the display and export.
                </p>
              )}
            </details>
          ))}
          <details>
            <summary>Change impact scope and limitations</summary>
            <p>
              Budgets: 20 selected indexed paths, 1,000 graph types, 3,000 graph
              references, 200 returned dependant types, and 20 evidence edges
              per path. Exports contain the same bounded result shown here.
            </p>
            {current.limitations.map((limit) => (
              <p key={limit}>{limit}</p>
            ))}
          </details>
        </>
      )}
    </section>
  );
}
