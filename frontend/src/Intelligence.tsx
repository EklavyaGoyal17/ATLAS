import { useEffect, useRef, useState } from "react";
import {
  useIsFetching,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { api } from "./api";
import { Notice } from "./ui";
import { SourceMap } from "./SourceMap";
import { ArchitectureReview } from "./ArchitectureReview";
import { ChangeImpact } from "./ChangeImpact";
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
  origin?: {
    kind: "LOCAL" | "GITHUB";
    repositoryUrl: string;
    commit: string;
    directory: string;
  };
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
type ImportRequest =
  | { kind: "LOCAL"; path: string; approved: boolean }
  | { kind: "GITHUB"; url: string; directory: string; approved: boolean };
type RepositoryMetadata = {
  snapshotId: string;
  modules: string[];
  languages: {
    language: string;
    files: number;
    lines: number;
    contentBytes: number;
    paths: string[];
  }[];
  dependencies: {
    ecosystem: string;
    name: string;
    declaredVersion: string;
    path: string;
    line: number;
  }[];
  signals: string[];
  limitations: string[];
};

function RepositoryInventory({
  report,
  indexedFiles,
  open,
}: {
  report: RepositoryMetadata;
  indexedFiles: Source[];
  open: (path: string, line: number) => void;
}) {
  const [language, setLanguage] = useState("");
  const [pathSearch, setPathSearch] = useState("");
  const indexedPaths = new Set(indexedFiles.map((file) => file.path));
  const paths = report.languages
    .filter((entry) => !language || entry.language === language)
    .flatMap((entry) => entry.paths)
    .filter(
      (path) =>
        indexedPaths.has(path) &&
        path.toLowerCase().includes(pathSearch.toLowerCase()),
    );
  return (
    <section
      className="panel padded repository-inventory"
      aria-labelledby="repository-inventory-title"
    >
      <h2 id="repository-inventory-title">Repository inventory</h2>
      <p>
        Counts cover indexed, redacted text in this snapshot. They do not count
        the entire GitHub repository or Git history. Language groups follow file
        extensions; structured AST analysis currently covers Java only.
      </p>
      <div className="table-scroll">
        <table>
          <caption>Indexed text by language or file extension</caption>
          <thead>
            <tr>
              <th scope="col">Language / extension</th>
              <th scope="col">Files</th>
              <th scope="col">Lines</th>
              <th scope="col">Redacted UTF-8 text bytes</th>
            </tr>
          </thead>
          <tbody>
            {report.languages.map((entry) => (
              <tr key={entry.language}>
                <th scope="row">{entry.language}</th>
                <td>{entry.files.toLocaleString()}</td>
                <td>{entry.lines.toLocaleString()}</td>
                <td>{entry.contentBytes.toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="form-row inventory-filters">
        <label>
          Filter indexed language
          <select
            value={language}
            onChange={(e) => setLanguage(e.target.value)}
          >
            <option value="">All indexed languages</option>
            {report.languages.map((entry) => (
              <option key={entry.language} value={entry.language}>
                {entry.language}
              </option>
            ))}
          </select>
        </label>
        <label>
          Search indexed paths
          <input
            value={pathSearch}
            onChange={(e) => setPathSearch(e.target.value)}
            placeholder="e.g. src/main.py"
          />
        </label>
      </div>
      <p role="status">{paths.length} matching indexed files</p>
      {paths.length ? (
        <ul className="inventory-paths">
          {paths.map((path) => (
            <li key={path}>
              <button className="text-button" onClick={() => open(path, 1)}>
                Open {path}
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <p>
          No indexed paths match these filters. Choose another language or clear
          the path search.
        </p>
      )}
    </section>
  );
}
function githubOriginLink(origin: Snapshot["origin"]) {
  if (
    origin?.kind !== "GITHUB" ||
    !/^https:\/\/github\.com\/[A-Za-z0-9][A-Za-z0-9-]*\/[A-Za-z0-9_.-]+$/.test(
      origin.repositoryUrl,
    ) ||
    !/^[a-f0-9]{40}$/.test(origin.commit)
  )
    return null;
  return `${origin.repositoryUrl}/tree/${origin.commit}`;
}
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
  const [importSource, setImportSource] = useState<"LOCAL" | "GITHUB">("LOCAL");
  const [githubUrl, setGithubUrl] = useState("");
  const [directory, setDirectory] = useState("");
  const [githubApproved, setGithubApproved] = useState(false);
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState("");
  const [line, setLine] = useState(0);
  const [question, setQuestion] = useState(
    "What are the main entry points and declared dependencies in this repository?",
  );
  const [transmit, setTransmit] = useState(false);
  const [preview, setPreview] = useState<Context[] | null>(null);
  const intent = useRef(0);
  const importIntent = useRef(0);
  useEffect(
    () => () => {
      importIntent.current++;
    },
    [],
  );
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
    onMutate: async () => {
      const version = ++importIntent.current;
      invalidateAssistance();
      await cache.cancelQueries({ queryKey: ["repository"] });
      return { version };
    },
    mutationFn: (request: ImportRequest) =>
      api<Snapshot>(
        token,
        request.kind === "GITHUB" ? "/repository/github" : "/repository/import",
        {
          method: "POST",
          // GitHub metadata, archive headers/body and parsing have separate
          // bounded server stages. Keep the client deadline above their total.
          timeoutMs: request.kind === "GITHUB" ? 95000 : undefined,
          body:
            request.kind === "GITHUB"
              ? {
                  url: request.url,
                  directory: request.directory,
                  approved: request.approved,
                }
              : { path: request.path, approved: request.approved },
        },
      ),
    onSuccess: (r, _request, context) => {
      if (context?.version !== importIntent.current) return;
      cache.setQueryData(["repository"], r);
      void cache.invalidateQueries({ queryKey: ["repo-config"] });
      setSelected(r.files[0]?.path ?? "");
      setLine(0);
      invalidateAssistance();
    },
  });
  const clear = useMutation({
    onMutate: async () => {
      const version = ++importIntent.current;
      invalidateAssistance();
      await cache.cancelQueries({ queryKey: ["repository"] });
      return { version };
    },
    mutationFn: () => api(token, "/repository", { method: "DELETE" }),
    onSuccess: (_result, _variables, context) => {
      if (context?.version !== importIntent.current) return;
      cache.removeQueries({ queryKey: ["repository"] });
      cache.removeQueries({ queryKey: ["architecture-review"] });
      cache.removeQueries({ queryKey: ["repository-metadata"] });
      cache.removeQueries({ queryKey: ["git"] });
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
  const importBusy = importRepo.isPending || clear.isPending;
  const originLink = githubOriginLink(data?.origin);
  // Both analyses use the server's bounded architecture gate. An unmounted
  // query still completes its HTTP request, so wait for that request before
  // mounting analysis for a replacement snapshot.
  const architectureRequests = useIsFetching({
    queryKey: ["architecture-review"],
  });
  const supersededArchitectureRequests = useIsFetching({
    queryKey: ["architecture-review"],
    predicate: (query) => query.queryKey[1] !== data?.id,
  });
  const architectureState = cache.getQueryState<{ snapshotId: string }>([
    "architecture-review",
    data?.id,
  ]);
  const architectureSettled =
    architectureRequests === 0 &&
    (architectureState?.status === "error" ||
      architectureState?.data?.snapshotId === data?.id);
  const currentAnswer =
    answer.data?.version === intent.current ? answer.data.result : null;
  const metadata = useQuery({
    queryKey: ["repository-metadata", data?.id],
    queryFn: () =>
      api<RepositoryMetadata>(
        token,
        `/repository/metadata?snapshotId=${encodeURIComponent(data!.id)}`,
      ),
    enabled: !!data && !importBusy,
    retry: false,
  });
  const currentMetadata =
    !importBusy && metadata.data?.snapshotId === data?.id
      ? metadata.data
      : null;
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
      <section className="panel padded repository-import">
        <h2>Explore a code repository</h2>
        <p>
          Use ATLAS with frontend, backend, library, or full-stack projects.
          Supported text files, including TypeScript and JavaScript, can be
          browsed and searched. Structured dependency, cycle, and change-impact
          analysis currently covers Java. The built-in synthetic payments lab is
          a separate learning example.
        </p>
        <fieldset className="repository-import-source" disabled={importBusy}>
          <legend>Repository source</legend>
          <label className="check-label">
            <input
              type="radio"
              name="repository-source"
              checked={importSource === "LOCAL"}
              onChange={() => {
                setImportSource("LOCAL");
                setApproved(false);
                setGithubApproved(false);
                importRepo.reset();
              }}
            />
            Local folder
          </label>
          <label className="check-label">
            <input
              type="radio"
              name="repository-source"
              checked={importSource === "GITHUB"}
              onChange={() => {
                setImportSource("GITHUB");
                setApproved(false);
                setGithubApproved(false);
                importRepo.reset();
              }}
            />
            GitHub link
          </label>
        </fieldset>
        {importSource === "LOCAL" ? (
          <>
            <h3>Approve a local Git workspace</h3>
            <p>
              Read-only snapshot of the configured workspace. Files stay local
              unless you separately approve a provider request below.
            </p>
            <div className="form-row">
              <label>
                Repository path relative to configured root
                <input
                  value={path}
                  maxLength={200}
                  disabled={importBusy}
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
                  disabled={importBusy}
                  onChange={(e) => setApproved(e.target.checked)}
                />
                I approve reading this repository
              </label>
              <button
                className="primary"
                disabled={!approved || importBusy}
                onClick={() =>
                  importRepo.mutate({ kind: "LOCAL", path, approved })
                }
              >
                {importRepo.isPending ? "Indexing…" : "Import repository"}
              </button>
            </div>
          </>
        ) : (
          <>
            <h3>Approve a public GitHub repository</h3>
            <p>
              Paste a public repository link such as
              https://github.com/owner/repository. ATLAS downloads a read-only
              snapshot of its default branch and records the exact commit. No
              GitHub API key is needed. Private repositories and personal access
              tokens are not supported here.
            </p>
            <div className="form-row">
              <label>
                Public GitHub repository URL
                <input
                  type="url"
                  value={githubUrl}
                  maxLength={300}
                  disabled={importBusy}
                  placeholder="https://github.com/owner/repository"
                  onChange={(e) => {
                    setGithubUrl(e.target.value);
                    setGithubApproved(false);
                  }}
                />
              </label>
              <label>
                Repository subdirectory (optional)
                <input
                  value={directory}
                  maxLength={200}
                  disabled={importBusy}
                  placeholder="e.g. frontend/src"
                  onChange={(e) => {
                    setDirectory(e.target.value);
                    setGithubApproved(false);
                  }}
                />
              </label>
            </div>
            <div className="button-row">
              <label className="check-label">
                <input
                  type="checkbox"
                  checked={githubApproved}
                  disabled={importBusy}
                  onChange={(e) => setGithubApproved(e.target.checked)}
                />
                I approve downloading this public repository
              </label>
              <button
                className="primary"
                disabled={!githubApproved || !githubUrl.trim() || importBusy}
                onClick={() =>
                  importRepo.mutate({
                    kind: "GITHUB",
                    url: githubUrl,
                    directory,
                    approved: githubApproved,
                  })
                }
              >
                {importRepo.isPending
                  ? "Downloading and indexing…"
                  : "Import GitHub repository"}
              </button>
            </div>
            <p className="muted">
              HTTPS github.com repository links only. Select a subdirectory to
              limit indexed files in a larger project; source paths retain that
              prefix. The archive is limited to 16 MiB compressed, 32 MiB
              expanded, and 5,000 entries, even with a subdirectory selected.
            </p>
          </>
        )}
        <p>
          Import reads source only. No builds, scripts, package installs, or
          repository services are started. Importing makes no external AI
          request; provider transmission requires separate excerpt approval.
        </p>
        <p className="muted">
          500 files · 4 MB total · 160,000 bytes/file · Java AST analysis; other
          supported text formats are searchable. Credentials are masked
          heuristically. Review files before sharing.
        </p>
        {data && (
          <>
            {data.origin?.kind === "GITHUB" && (
              <div
                className="repository-origin"
                aria-label="Imported repository provenance"
              >
                <h3>GitHub snapshot provenance</h3>
                <p>
                  {originLink ? (
                    <a href={originLink} target="_blank" rel="noreferrer">
                      {data.origin.repositoryUrl}
                    </a>
                  ) : (
                    data.origin.repositoryUrl
                  )}
                </p>
                <p>
                  Commit: <code>{data.origin.commit}</code>
                </p>
                <p>Scope: {data.origin.directory || "entire repository"}</p>
              </div>
            )}
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
                disabled={importBusy}
                onClick={() => clear.mutate()}
              >
                Delete imported snapshot
              </button>
            </div>
          </>
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
                disabled={answer.isPending || importBusy}
                onClick={() => answer.mutate(false)}
              >
                Find local evidence
              </button>
              <button
                className="secondary"
                disabled={review.isPending || importBusy}
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
                    importBusy
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
          {!importBusy && metadata.isPending && (
            <p role="status">Reading indexed repository inventory…</p>
          )}
          {!importBusy && metadata.isError && (
            <button
              className="secondary"
              disabled={metadata.isFetching}
              onClick={() =>
                void cache
                  .invalidateQueries({ queryKey: ["repository"] })
                  .then(() =>
                    cache.invalidateQueries({
                      queryKey: ["repository-metadata"],
                    }),
                  )
              }
            >
              Retry repository inventory
            </button>
          )}
          {!importBusy && metadata.data && !currentMetadata && (
            <p className="callout">
              The repository changed while inventory was being read.{" "}
              <button
                className="text-button"
                onClick={() =>
                  void cache
                    .invalidateQueries({ queryKey: ["repository"] })
                    .then(() =>
                      cache.invalidateQueries({
                        queryKey: ["repository-metadata"],
                      }),
                    )
                }
              >
                Refresh repository inventory
              </button>
            </p>
          )}
          {currentMetadata && (
            <RepositoryInventory
              key={`${token}:${data.id}`}
              report={currentMetadata}
              indexedFiles={data.files}
              open={open}
            />
          )}
          <div className="two-columns repository-layout">
            <section className="panel padded">
              <h2>Symbols & files</h2>
              <label>
                Search symbols
                <input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="controller, component, service…"
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
                {file?.content
                  .split(/\r\n|[\n\v\f\r\u0085\u2028\u2029]/)
                  .map((text, i) => (
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
                  <summary>
                    {data.origin?.kind === "GITHUB"
                      ? "GitHub snapshot provenance and file scope"
                      : "Git history and current diff"}
                  </summary>
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
            {currentMetadata && (
              <details>
                <summary>
                  Modules, framework signals and manifest dependencies
                </summary>
                <p>
                  Folders:{" "}
                  {currentMetadata.modules.join(", ") ||
                    "No indexed top-level folders"}
                </p>
                <p>
                  {currentMetadata.signals.join(" · ") ||
                    "No framework signals found in indexed text."}
                </p>
                {!currentMetadata.dependencies.length && (
                  <p>
                    No supported dependency declarations were found in indexed
                    manifests. Review the format limitations below.
                  </p>
                )}
                {currentMetadata.dependencies.map((d, i) => (
                  <p key={i}>
                    <code>
                      {d.ecosystem}: {d.name} · {d.declaredVersion}
                    </code>
                    <button
                      className="text-button"
                      onClick={() =>
                        open(
                          d.path,
                          Number.isInteger(d.line) && d.line > 0 ? d.line : 1,
                        )
                      }
                    >
                      Open {d.path}
                      {Number.isInteger(d.line) && d.line > 0
                        ? `:${d.line}`
                        : " (file evidence)"}
                    </button>
                  </p>
                ))}
                {currentMetadata.limitations.map((l) => (
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
          {!importBusy && supersededArchitectureRequests === 0 && (
            <ArchitectureReview
              key={`architecture:${token}:${data.id}`}
              token={token}
              snapshotId={data.id}
              open={open}
            />
          )}
          {!importBusy && supersededArchitectureRequests > 0 && (
            <p role="status">Finishing the previous repository analysis…</p>
          )}
          {!importBusy && architectureSettled && (
            <ChangeImpact
              key={`impact:${token}:${data.id}`}
              token={token}
              snapshotId={data.id}
              files={data.files}
              open={open}
            />
          )}
        </>
      )}
    </div>
  );
}
