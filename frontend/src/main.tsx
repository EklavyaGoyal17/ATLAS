import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  QueryClient,
  QueryClientProvider,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  Activity,
  ArrowRight,
  ArrowUpRight,
  BookOpen,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  CreditCard,
  Database,
  FlaskConical,
  Layers3,
  LayoutDashboard,
  LockKeyhole,
  LogOut,
  Plus,
  RefreshCw,
  Search,
  ShieldCheck,
  Terminal,
  X,
} from "lucide-react";
import {
  api,
  money,
  timestamp,
  type Authorization,
  type PaymentPage,
  type Reconciliation,
  type Result,
} from "./api";
import { Authorize } from "./AuthorizationDialog";
import { PaymentDetail } from "./PaymentDetail";
import { Ledger } from "./Ledger";
import { Guide } from "./Guide";
import { SystemsLab } from "./SystemsLab";
import { Observability } from "./Observability";
import { Intelligence } from "./Intelligence";
import { Studio } from "./Studio";
import { Growth } from "./Growth";
import { Modal, Notice, Stat, Status } from "./ui";
import "./style.css";

type View =
  | "overview"
  | "payments"
  | "ledger"
  | "systems"
  | "guide"
  | "observability"
  | "intelligence"
  | "assistant"
  | "studio"
  | "growth";
const nav: { id: View; label: string; icon: typeof Activity }[] = [
  { id: "overview", label: "Overview", icon: LayoutDashboard },
  { id: "payments", label: "Payments lab", icon: CreditCard },
  { id: "systems", label: "Systems lab", icon: FlaskConical },
  { id: "ledger", label: "Ledger & reconciliation", icon: Layers3 },
  { id: "observability", label: "Observability", icon: Activity },
  { id: "intelligence", label: "Code intelligence", icon: Terminal },
  { id: "assistant", label: "AI engineer", icon: Search },
  { id: "studio", label: "Architecture studio", icon: Layers3 },
  { id: "growth", label: "Engineering growth", icon: BookOpen },
  { id: "guide", label: "Engineering field guide", icon: BookOpen },
];
function App() {
  const cache = useQueryClient();
  const [token, setToken] = useState("");
  const [draftToken, setDraftToken] = useState("");
  const [view, setView] = useState<View>("overview");
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string | null>(
    new URLSearchParams(location.search).get("payment"),
  );
  const [creating, setCreating] = useState(false);
  const [palette, setPalette] = useState(false);
  const [search, setSearch] = useState("");
  const [last, setLast] = useState<{ body: Authorization; key: string } | null>(
    null,
  );
  const [feedback, setFeedback] = useState("");
  const history = useQuery({
    queryKey: ["payments", page],
    queryFn: () => api<PaymentPage>(token, `/payments?page=${page}&size=10`),
    enabled: !!token,
    retry: false,
  });
  const ledger = useQuery({
    queryKey: ["ledger"],
    queryFn: () => api<Reconciliation[]>(token, "/ledger/reconciliation"),
    enabled: !!token,
    retry: false,
  });
  const refresh = () => cache.invalidateQueries();
  const connect = useMutation({
    mutationFn: () => api<PaymentPage>(draftToken, "/payments?page=0&size=10"),
    onSuccess: () => {
      cache.clear();
      setToken(draftToken);
      setDraftToken("");
    },
  });
  const replay = useMutation({
    mutationFn: () =>
      api<Result>(token, "/payments", {
        method: "POST",
        body: last!.body,
        key: last!.key,
      }),
    onSuccess: (r) => {
      setSelected(r.payment.id);
      setFeedback(
        r.replayed
          ? "Safely replayed · original response returned, no new payment."
          : "Authorization created.",
      );
      void refresh();
    },
  });
  useEffect(() => {
    function shortcut(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key === "k") {
        e.preventDefault();
        setPalette((p) => !p);
      }
    }
    window.addEventListener("keydown", shortcut);
    return () => window.removeEventListener("keydown", shortcut);
  }, []);
  useEffect(() => {
    const url = new URL(location.href);
    selected
      ? url.searchParams.set("payment", selected)
      : url.searchParams.delete("payment");
    historyGlobalReplace(url);
  }, [selected]);
  const records = history.data?.items ?? [];
  return (
    <div className="app">
      <aside className="sidebar">
        <a
          className="brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            setView("overview");
          }}
          aria-label="ATLAS home"
        >
          <span className="brand-mark">
            A<span />
          </span>
          <strong>ATLAS</strong>
          <span className="version">LAB</span>
        </a>
        <div className="workspace">
          <span className="workspace-icon">
            <Terminal size={17} />
          </span>
          <div>
            Personal workspace<small>Local development</small>
          </div>
          <span className="live-dot" />
        </div>
        <p className="nav-label">WORKSPACE</p>
        <nav aria-label="Main navigation">
          {nav.map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              aria-label={label}
              className={view === id ? "nav-item active" : "nav-item"}
              onClick={() => {
                setView(id);
                setSelected(null);
                setFeedback("");
              }}
              aria-current={view === id ? "page" : undefined}
            >
              <Icon size={18} />
              <span>{label}</span>
              {id === "payments" && <span className="nav-tag">01</span>}
            </button>
          ))}
        </nav>
        <div className="sidebar-note">
          <FlaskConical size={20} />
          <strong>A laboratory for better engineering.</strong>
          <p>
            Real invariants.
            <br />
            Synthetic transactions.
          </p>
          <button onClick={() => setView("guide")}>
            Explore the field guide <ArrowUpRight size={14} />
          </button>
        </div>
        <div className="sidebar-bottom">
          <span className="avatar">LE</span>
          <div>
            Local engineer<small>Independent project</small>
          </div>
          {token && (
            <button
              className="icon-button"
              aria-label="Disconnect"
              onClick={() => {
                setToken("");
                setSelected(null);
                setLast(null);
                setFeedback("");
                setDraftToken("");
                connect.reset();
                cache.clear();
              }}
            >
              <LogOut size={16} />
            </button>
          )}
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <div className="breadcrumb">
            Workspace <ChevronRight size={13} />{" "}
            <span>{nav.find((n) => n.id === view)?.label}</span>
          </div>
          <div className="topbar-actions">
            <button className="search-button" onClick={() => setPalette(true)}>
              <Search size={15} />
              <span>Jump to…</span>
              <kbd>Ctrl K</kbd>
            </button>
            <span className="environment">
              <i />
              {token ? "Connected locally" : "Local environment"}
            </span>
          </div>
        </header>
        {!token ? (
          <main className="connect-page">
            <div className="eyebrow">
              <span /> YOUR ENGINEERING COMMAND CENTER
            </div>
            <h1>
              Understand the system.
              <br />
              <span>Trust the evidence.</span>
            </h1>
            <p>
              Investigate payment workflows, test your assumptions,
              <br />
              and see what correctness looks like in code.
            </p>
            <form
              className="connect-card"
              onSubmit={(e) => {
                e.preventDefault();
                connect.mutate();
              }}
            >
              <div className="section-icon">
                <LockKeyhole size={21} />
              </div>
              <h2>Connect to your local lab</h2>
              <p>
                Use the bearer token configured in your environment. Your token
                stays in memory for this session.
              </p>
              <label htmlFor="token">Workspace token</label>
              <input
                id="token"
                type="password"
                autoComplete="off"
                value={draftToken}
                onChange={(e) => setDraftToken(e.target.value)}
                required
                minLength={32}
                placeholder="Enter ATLAS_API_TOKEN"
              />
              <Notice error={connect.error} />
              <button className="primary" disabled={connect.isPending}>
                {connect.isPending ? "Connecting…" : "Open workspace"}
                <ArrowRight size={16} />
              </button>
            </form>
            <div className="safe-note">
              <ShieldCheck size={15} /> Independent project · Synthetic data
              only · No external AI calls
            </div>
          </main>
        ) : (
          <main className="content">
            <div className="page-heading">
              <div>
                <div className="eyebrow">
                  {view === "guide"
                    ? "LEARN THROUGH THE IMPLEMENTATION"
                    : "ENGINEERING WORKSPACE"}{" "}
                  <span className="slash">/</span> LOCAL
                </div>
                <h1>
                  {view === "overview"
                    ? "Your systems, understood."
                    : view === "payments"
                      ? "Payments lab"
                      : view === "ledger"
                        ? "Follow the money."
                        : view === "systems"
                          ? "Failure, made inspectable."
                          : view === "guide"
                            ? "The engineering field guide"
                            : nav.find((n) => n.id === view)?.label}
                </h1>
                <p>
                  {view === "overview"
                    ? "A clear view of your payment laboratory. Every number comes from your database."
                    : view === "payments"
                      ? "Create, inspect, and safely repeat synthetic payment workflows."
                      : view === "ledger"
                        ? "Balanced postings are the beginning. Reconcile them against business state."
                        : view === "systems"
                          ? "Step through durable delivery, interrupt a worker, and verify the recovery."
                          : view === "guide"
                            ? "The decisions, invariants, and questions behind this laboratory."
                            : "Investigate with evidence. Record the reasoning. Keep control of your data."}
                </p>
              </div>
              <div className="heading-actions">
                <button
                  className="secondary"
                  aria-label="Refresh data"
                  onClick={() => void refresh()}
                >
                  <RefreshCw size={16} />
                </button>
                {["overview", "payments", "ledger"].includes(view) && (
                  <button className="primary" onClick={() => setCreating(true)}>
                    <Plus size={17} /> New authorization
                  </button>
                )}
              </div>
            </div>
            <Notice error={history.error || ledger.error || replay.error} />
            {feedback && (
              <div className="feedback" role="status">
                <CheckCircle2 size={17} />
                {feedback}
                <button
                  className="icon-button"
                  aria-label="Dismiss message"
                  onClick={() => setFeedback("")}
                >
                  <X size={14} />
                </button>
              </div>
            )}
            {view === "observability" ? (
              <Observability token={token} />
            ) : view === "intelligence" || view === "assistant" ? (
              <Intelligence token={token} assistant={view === "assistant"} />
            ) : view === "studio" ? (
              <Studio token={token} />
            ) : view === "growth" ? (
              <Growth token={token} />
            ) : view === "systems" ? (
              <SystemsLab
                token={token}
                inspectPayment={(id) => setSelected(id)}
              />
            ) : history.isPending ? (
              <div className="loading" role="status">
                Loading your workspace…
              </div>
            ) : view === "ledger" ? (
              <Ledger rows={ledger.data ?? []} />
            ) : view === "guide" ? (
              <Guide />
            ) : (
              <>
                {view === "overview" && (
                  <>
                    <div className="hero-strip">
                      <div>
                        <span className="hero-label">
                          <FlaskConical size={15} /> THE CORRECTNESS LAB
                        </span>
                        <h2>
                          Small experiments.
                          <br />
                          Lasting understanding.
                        </h2>
                        <p>
                          Follow a request from authorization to ledger.
                          <br />
                          Then repeat it. The business effect happens once.
                        </p>
                        <button
                          onClick={() => {
                            setView("payments");
                            setCreating(true);
                          }}
                        >
                          Start an experiment <ArrowRight size={16} />
                        </button>
                      </div>
                      <div
                        className="flow-illustration"
                        aria-label="Authorization to capture to balanced ledger"
                      >
                        <div>
                          <CreditCard size={23} />
                          <span>Authorize</span>
                        </div>
                        <span className="flow-line" />
                        <div>
                          <ShieldCheck size={23} />
                          <span>Capture</span>
                        </div>
                        <span className="flow-line" />
                        <div>
                          <Layers3 size={23} />
                          <span>Ledger</span>
                        </div>
                        <small>
                          <span className="live-dot" /> Atomic by design
                        </small>
                      </div>
                    </div>
                    <div className="stats">
                      <Stat
                        title="Total payments"
                        value={history.data?.total ?? "—"}
                        caption="Persisted synthetic records"
                        icon={CreditCard}
                      />
                      <Stat
                        title="Postings"
                        value={
                          ledger.data
                            ? ledger.data.reduce(
                                (n, r) => n + r.postingCount,
                                0,
                              )
                            : "—"
                        }
                        caption="Each pairs a debit and a credit"
                        icon={Layers3}
                      />
                      <Stat
                        title="Reconciliation"
                        value={
                          ledger.data
                            ? ledger.data.every((r) => r.balanced)
                              ? "Balanced"
                              : "Investigate"
                            : "—"
                        }
                        caption="Across USD, EUR, and INR"
                        icon={ShieldCheck}
                      />
                    </div>
                  </>
                )}
                <section className="panel">
                  <div className="panel-heading">
                    <div>
                      <h2>
                        {view === "overview"
                          ? "Recent transactions"
                          : "Transaction history"}{" "}
                        <span className="count">
                          {history.data?.total ?? "—"}
                        </span>
                      </h2>
                      <p>
                        Orbit Supply <span>·</span> Synthetic merchant
                      </p>
                    </div>
                    {last && (
                      <button
                        className="secondary"
                        disabled={replay.isPending}
                        onClick={() => replay.mutate()}
                      >
                        <RefreshCw size={14} />
                        {replay.isPending
                          ? "Replaying…"
                          : "Replay last authorization"}
                      </button>
                    )}
                  </div>
                  {records.length ? (
                    <div
                      className="table-scroll"
                      role="region"
                      aria-label="Transaction history table"
                      tabIndex={0}
                    >
                      <table>
                        <thead>
                          <tr>
                            <th>TRANSACTION</th>
                            <th>STATUS</th>
                            <th>AMOUNT</th>
                            <th>CREATED</th>
                            <th>
                              <span className="sr-only">Open</span>
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          {records.map((p) => (
                            <tr key={p.id}>
                              <td>
                                <button
                                  className="transaction-link"
                                  onClick={() => setSelected(p.id)}
                                >
                                  <span className="transaction-icon">
                                    <ArrowUpRight size={17} />
                                  </span>
                                  <span>
                                    <strong>
                                      {p.id.slice(0, 8)}
                                      <span className="muted">…</span>
                                    </strong>
                                    <small>merchant_demo</small>
                                  </span>
                                </button>
                              </td>
                              <td>
                                <Status value={p.status} />
                              </td>
                              <td className="amount">
                                {money(p.amountMinor, p.currency)}
                                <small>{p.currency}</small>
                              </td>
                              <td className="date-cell">
                                {timestamp(p.createdAt)}
                              </td>
                              <td>
                                <button
                                  className="icon-button"
                                  aria-label={`Inspect payment ${p.id}`}
                                  onClick={() => setSelected(p.id)}
                                >
                                  <ArrowUpRight size={17} />
                                </button>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  ) : history.error ? (
                    <div className="empty">
                      <h3>Transaction history is unavailable.</h3>
                      <p>
                        Check the error above and retry when the API is
                        available.
                      </p>
                      <button
                        className="secondary"
                        onClick={() => void refresh()}
                      >
                        Retry history
                      </button>
                    </div>
                  ) : (
                    <div className="empty">
                      <CreditCard size={32} />
                      <h3>Your first experiment starts here.</h3>
                      <p>
                        Create a synthetic authorization to see its state, audit
                        timeline, and ledger.
                      </p>
                      <button
                        className="secondary"
                        onClick={() => setCreating(true)}
                      >
                        Create authorization <ArrowRight size={15} />
                      </button>
                    </div>
                  )}
                  <div className="table-footer">
                    <span>Page {page + 1} · Up to 10 records per page</span>
                    <div>
                      <button
                        className="icon-button"
                        aria-label="Previous page"
                        disabled={page === 0}
                        onClick={() => setPage((p) => p - 1)}
                      >
                        <ChevronLeft size={16} />
                      </button>
                      <button
                        className="icon-button"
                        aria-label="Next page"
                        disabled={(page + 1) * 10 >= (history.data?.total ?? 0)}
                        onClick={() => setPage((p) => p + 1)}
                      >
                        <ChevronRight size={16} />
                      </button>
                    </div>
                  </div>
                </section>
                <div className="bottom-grid">
                  <div className="principle-card">
                    <div className="section-icon">
                      <Database size={20} />
                    </div>
                    <div>
                      <h3>Committed together. Or not at all.</h3>
                      <p>
                        Payment state, audit event, ledger posting, and
                        idempotency response share one database transaction.
                      </p>
                      <button
                        className="text-button"
                        onClick={() => setView("guide")}
                      >
                        Understand the design <ArrowRight size={14} />
                      </button>
                    </div>
                  </div>
                  <div className="principle-card">
                    <div className="section-icon">
                      <ShieldCheck size={20} />
                    </div>
                    <div>
                      <h3>Built for safe investigation.</h3>
                      <p>
                        Fictional merchants. Explicit currencies. No real
                        credentials, funds, or customer information.
                      </p>
                      <span className="mini-label">EDUCATIONAL SIMULATION</span>
                    </div>
                  </div>
                </div>
              </>
            )}
            <footer>
              <span>
                ATLAS <span className="muted">/</span> Adaptive Technical
                Learning, Analysis & Systems
              </span>
              <span>Independent. Local. Evidence-led.</span>
            </footer>
          </main>
        )}
      </div>
      {creating && (
        <Authorize
          token={token}
          close={() => setCreating(false)}
          complete={(r, body, key) => {
            setLast({ body, key });
            setCreating(false);
            setSelected(r.payment.id);
            setFeedback(
              r.replayed
                ? "Original authorization replayed."
                : "Authorization committed. Inspect the evidence below.",
            );
            setPage(0);
            void refresh();
          }}
        />
      )}
      {selected && token && (
        <PaymentDetail
          token={token}
          id={selected}
          close={() => setSelected(null)}
          changed={() => void refresh()}
        />
      )}
      {palette && (
        <Modal
          title="Jump to a workspace"
          close={() => {
            setPalette(false);
            setSearch("");
          }}
        >
          <label htmlFor="jump">Search navigation</label>
          <input
            autoFocus
            id="jump"
            placeholder="Payments, ledger, field guide…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <div className="palette-results">
            {nav
              .filter((n) =>
                n.label.toLowerCase().includes(search.toLowerCase()),
              )
              .map((n) => (
                <button
                  key={n.id}
                  onClick={() => {
                    setView(n.id);
                    setPalette(false);
                    setSearch("");
                  }}
                >
                  <n.icon size={18} />
                  {n.label}
                  <ArrowRight size={16} />
                </button>
              ))}
          </div>
        </Modal>
      )}
    </div>
  );
}
function historyGlobalReplace(url: URL) {
  window.history.replaceState(null, "", url);
}
const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 10000, refetchOnWindowFocus: false },
    mutations: { retry: false },
  },
});
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>,
);
