import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./api";
import { Notice } from "./ui";
import { SourceMap } from "./SourceMap";
type Source = {
  path: string;
  language: string;
  lines: number;
  content: string;
  warnings: string[];
};
type Symbol = {
  path: string;
  line: number;
  endLine: number;
  name: string;
  kind: string;
  detail: string;
};
type Snapshot = {
  id: string;
  files: Source[];
  symbols: Symbol[];
  edges: {
    from: string;
    to: string;
    evidence: string;
    path: string;
    line: number;
  }[];
  calls: {
    caller: string;
    callee: string;
    path: string;
    line: number;
    targetPath: string;
    targetLine: number;
    evidence: string;
  }[];
  findings: string[];
  languages: Record<string, number>;
  bytes: number;
};
type Context = {
  id: string;
  path: string;
  startLine: number;
  endLine: number;
  text: string;
};
type Answer = {
  mode: string;
  answer: string;
  citations: Context[];
  uncertainty: string[];
};
export function Intelligence({
  token,
  assistant = false,
}: {
  token: string;
  assistant?: boolean;
}) {
  const cache = useQueryClient();
  const [path, setPath] = useState(".");
  const [approved, setApproved] = useState(false);
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState("");
  const [line, setLine] = useState(0);
  const [question, setQuestion] = useState(
    "Where does payment authorization enter the system, and where is its transaction boundary?",
  );
  const [transmit, setTransmit] = useState(false);
  const [preview, setPreview] = useState<Context[] | null>(null);
  const intent = useRef(0);
  const configuration = useQuery({
    queryKey: ["repo-config"],
    queryFn: () =>
      api<{ indexed: boolean }>(token, "/repository/configuration"),
  });
  const snapshot = useQuery({
    queryKey: ["repository"],
    queryFn: () => api<Snapshot>(token, "/repository"),
    enabled: configuration.data?.indexed === true,
    retry: false,
  });
  const importRepo = useMutation({
    onMutate: () => invalidateAssistance(),
    mutationFn: () =>
      api<Snapshot>(token, "/repository/import", {
        method: "POST",
        body: { path, approved },
      }),
    onSuccess: (r) => {
      cache.setQueryData(["repository"], r);
      void cache.invalidateQueries({ queryKey: ["repo-config"] });
      setSelected(r.files[0]?.path ?? "");
      invalidateAssistance();
    },
  });
  const clear = useMutation({
    onMutate: () => invalidateAssistance(),
    mutationFn: () => api(token, "/repository", { method: "DELETE" }),
    onSuccess: () => {
      cache.removeQueries({ queryKey: ["repository"] });
      void cache.invalidateQueries({ queryKey: ["repo-config"] });
      setSelected("");
      invalidateAssistance();
    },
  });
  const git = useQuery({
    queryKey: ["git", snapshot.data?.id, selected],
    queryFn: () =>
      api<{ history: string; changes: string }>(
        token,
        `/repository/git?path=${encodeURIComponent(selected)}`,
      ),
    enabled: !!snapshot.data && !!selected,
    retry: false,
  });
  const answer = useMutation({
    mutationFn: async (remote: boolean) => {
      const version = intent.current;
      const result = await api<Answer>(token, "/assistant", {
        method: "POST",
        body: {
          question,
          useProvider: remote,
          approved: remote && transmit,
          approvedIds: preview?.map((c) => c.id) ?? [],
        },
      });
      return { result, version };
    },
  });
  const review = useMutation({
    mutationFn: async () => {
      const version = intent.current;
      const result = await api<Context[]>(token, "/assistant/preview", {
        method: "POST",
        body: { question },
      });
      return { result, version };
    },
    onSuccess: (r) => {
      if (r.version !== intent.current) return;
      setPreview(r.result);
      setTransmit(false);
    },
  });
  const provider = useQuery({
    queryKey: ["provider-config"],
    queryFn: () =>
      api<{ configured: boolean }>(token, "/assistant/configuration"),
  });
  const data = snapshot.data;
  const currentAnswer =
    answer.data?.version === intent.current ? answer.data.result : null;
  const metadata = useQuery({
    queryKey: ["repository-metadata", data?.id],
    queryFn: () =>
      api<{
        modules: string[];
        dependencies: {
          ecosystem: string;
          name: string;
          declaredVersion: string;
          path: string;
        }[];
        signals: string[];
        limitations: string[];
      }>(token, "/repository/metadata"),
    enabled: !!data,
    retry: false,
  });
  const file = data?.files.find((f) => f.path === selected);
  function invalidateAssistance() {
    intent.current++;
    setPreview(null);
    setTransmit(false);
    answer.reset();
    review.reset();
  }
  const symbols =
    data?.symbols
      .filter((s) =>
        `${s.name} ${s.kind} ${s.path}`
          .toLowerCase()
          .includes(search.toLowerCase()),
      )
      .slice(0, 100) ?? [];
  function open(path: string, line: number) {
    setSelected(path);
    setLine(line);
    setTimeout(
      () =>
        document
          .getElementById(`source-line-${line}`)
          ?.scrollIntoView({ block: "center" }),
      0,
    );
  }
  return (
    <div className="module-stack">
      <Notice
        error={
          configuration.error ||
          snapshot.error ||
          importRepo.error ||
          clear.error ||
          git.error ||
          answer.error ||
          review.error ||
          metadata.error ||
          provider.error
        }
      />
      <section className="panel padded">
        <h2>Approve a local Git workspace</h2>
        <p>
          Read-only snapshot of the configured workspace. No builds, scripts,
          dependency installs, or repository instructions are executed. Files
          stay local unless you approve a provider request below.
        </p>
        <div className="form-row">
          <label>
            Repository path relative to configured root
            <input
              value={path}
              maxLength={200}
              onChange={(e) => {
                setPath(e.target.value);
                setApproved(false);
              }}
            />
          </label>
          <label className="check-label">
            <input
              type="checkbox"
              checked={approved}
              onChange={(e) => setApproved(e.target.checked)}
            />
            I approve reading this repository
          </label>
          <button
            className="primary"
            disabled={!approved || importRepo.isPending}
            onClick={() => importRepo.mutate()}
          >
            {importRepo.isPending ? "Indexing…" : "Import repository"}
          </button>
        </div>
        <p className="muted">
          500 files · 4 MB total · 160 KiB/file · Java AST analysis; other
          supported text formats are searchable. Credentials are masked
          heuristically. Review files before sharing.
        </p>
        {data && (
          <div className="button-row">
            <span>
              {data.files.length} files · {data.symbols.length} symbols ·{" "}
              {data.bytes.toLocaleString()} bytes ·{" "}
              {Object.entries(data.languages)
                .map(([k, v]) => `${k}: ${v}`)
                .join(" / ")}
            </span>
            <button
              className="secondary"
              disabled={clear.isPending}
              onClick={() => clear.mutate()}
            >
              Delete imported snapshot
            </button>
          </div>
        )}
      </section>
      {data && (
        <>
          <section className="panel padded">
            <h2>
              {assistant
                ? "Grounded engineering investigator"
                : "Ask about the indexed code"}
            </h2>
            <label>
              Engineering question
              <textarea
                value={question}
                maxLength={1500}
                onChange={(e) => {
                  setQuestion(e.target.value);
                  invalidateAssistance();
                }}
              />
            </label>
            <div className="button-row">
              <button
                className="primary"
                disabled={
                  answer.isPending || importRepo.isPending || clear.isPending
                }
                onClick={() => answer.mutate(false)}
              >
                Find local evidence
              </button>
              <button
                className="secondary"
                disabled={
                  review.isPending || importRepo.isPending || clear.isPending
                }
                onClick={() => review.mutate()}
              >
                Preview provider context
              </button>
            </div>
            <p className="muted">
              Provider:{" "}
              {provider.data?.configured
                ? "configured by the server operator"
                : "disabled; deterministic evidence works without a model"}
              . The assistant has no file-writing or command tools.
            </p>
            {preview && (
              <div className="context-preview">
                <h3>Exact redacted excerpts for approval</h3>
                {preview.map((c) => (
                  <details key={c.id}>
                    <summary>
                      {c.path}:{c.startLine}–{c.endLine}
                    </summary>
                    <pre>{c.text}</pre>
                  </details>
                ))}
                <label className="check-label">
                  <input
                    type="checkbox"
                    checked={transmit}
                    onChange={(e) => setTransmit(e.target.checked)}
                  />
                  I approve sending this question and these excerpts to the
                  configured provider
                </label>
                <button
                  className="secondary"
                  disabled={
                    !transmit ||
                    !provider.data?.configured ||
                    answer.isPending ||
                    importRepo.isPending ||
                    clear.isPending
                  }
                  onClick={() => answer.mutate(true)}
                >
                  Ask configured provider
                </button>
              </div>
            )}
            {currentAnswer && (
              <div className="answer" aria-live="polite">
                <strong>{currentAnswer.mode}</strong>
                <p className="preserve-lines">{currentAnswer.answer}</p>
                {currentAnswer.uncertainty.map((u) => (
                  <p className="callout" key={u}>
                    {u}
                  </p>
                ))}
                {currentAnswer.citations.map((c) => (
                  <details key={c.id} open>
                    <summary>
                      <button
                        className="text-button"
                        onClick={() => open(c.path, c.startLine)}
                      >
                        {c.path}:{c.startLine}–{c.endLine}
                      </button>
                    </summary>
                    <pre>{c.text}</pre>
                  </details>
                ))}
              </div>
            )}
          </section>
          <div className="two-columns repository-layout">
            <section className="panel padded">
              <h2>Symbols & files</h2>
              <label>
                Search symbols
                <input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="authorize, transaction, PaymentStore…"
                />
              </label>
              <div className="symbol-list">
                {symbols.map((s, i) => (
                  <button
                    key={`${s.path}-${i}`}
                    onClick={() => open(s.path, s.line)}
                  >
                    <strong>{s.name}</strong>
                    <span>
                      {s.kind} · {s.path}:{s.line}
                    </span>
                    <small>{s.detail}</small>
                  </button>
                ))}
              </div>
              <label>
                Open indexed file
                <select
                  value={selected}
                  onChange={(e) => {
                    setSelected(e.target.value);
                    setLine(0);
                  }}
                >
                  <option value="">Choose a source file</option>
                  {data.files.map((f) => (
                    <option key={f.path}>{f.path}</option>
                  ))}
                </select>
              </label>
            </section>
            <section className="panel padded source-panel">
              <h2>{file?.path ?? "Source viewer"}</h2>
              {file?.warnings.map((w) => (
                <p className="callout" key={w}>
                  {w}
                </p>
              ))}
              <pre className="source-code">
                {file?.content.split(/\r?\n/).map((text, i) => (
                  <div
                    id={`source-line-${i + 1}`}
                    className={line === i + 1 ? "highlight-line" : ""}
                    key={i}
                  >
                    <span className="line-number">{i + 1}</span>
                    {text || " "}
                  </div>
                ))}
              </pre>
              {selected && (
                <details>
                  <summary>Git history and current diff</summary>
                  <pre>{git.data?.history || "No recorded history"}</pre>
                  <pre>
                    {git.data?.changes ||
                      "No tracked changes; untracked files have no Git diff."}
                  </pre>
                </details>
              )}
            </section>
          </div>
          <section className="panel padded">
            <h2>Declared dependency map</h2>
            {metadata.data && (
              <details>
                <summary>
                  Modules, framework signals and manifest dependencies
                </summary>
                <p>Folders: {metadata.data.modules.join(", ")}</p>
                <p>{metadata.data.signals.join(" · ")}</p>
                {metadata.data.dependencies.map((d, i) => (
                  <p key={i}>
                    <code>
                      {d.ecosystem}: {d.name} · {d.declaredVersion}
                    </code>
                    <button
                      className="text-button"
                      onClick={() => open(d.path, 1)}
                    >
                      Open {d.path}
                    </button>
                  </p>
                ))}
                {metadata.data.limitations.map((l) => (
                  <p className="muted" key={l}>
                    {l}
                  </p>
                ))}
              </details>
            )}
            <SourceMap edges={data.edges} open={open} />
            <p>
              Edges come from Java field types in the AST. Select an edge to
              inspect its declaration. This graph establishes declarations;
              runtime calls need traces or further analysis.
            </p>
            <div className="dependency-map">
              {data.edges.map((edge, i) => (
                <button
                  className="dependency-edge"
                  key={i}
                  onClick={() => open(edge.path, edge.line)}
                >
                  <strong>{edge.from}</strong>
                  <span>→ {edge.evidence}</span>
                  <strong>{edge.to}</strong>
                </button>
              ))}
            </div>
            <details>
              <summary>
                Maintainability findings ({data.findings.length})
              </summary>
              {data.findings.slice(0, 100).map((f, i) => (
                <p key={i}>{f}</p>
              ))}
            </details>
          </section>
          <section className="panel padded">
            <h2>Source-supported request paths</h2>
            <p>
              Potential calls resolved from unambiguous declared types and
              method arity. Inspect source at both ends. Runtime execution,
              branches and proxy behavior require traces and tests.
            </p>
            <div className="dependency-map">
              {data.calls
                .filter(
                  (c) =>
                    !search ||
                    `${c.caller} ${c.callee}`
                      .toLowerCase()
                      .includes(search.toLowerCase()),
                )
                .slice(0, 100)
                .map((c, i) => (
                  <article className="dependency-edge" key={i}>
                    <button
                      className="text-button"
                      onClick={() => open(c.path, c.line)}
                    >
                      {c.caller}:{c.line}
                    </button>
                    <span>→</span>
                    <button
                      className="text-button"
                      onClick={() => open(c.targetPath, c.targetLine)}
                    >
                      {c.callee}:{c.targetLine}
                    </button>
                  </article>
                ))}
            </div>
          </section>
        </>
      )}
    </div>
  );
}
