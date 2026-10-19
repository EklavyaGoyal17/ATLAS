import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  ArrowRight,
  CheckCircle2,
  Clock3,
  Database,
  FlaskConical,
  Layers3,
  Pause,
  Play,
  RefreshCw,
  SkipForward,
} from "lucide-react";
import { api, timestamp } from "./api";
import { Notice, Stat, Status } from "./ui";
import { ModelLab } from "./ModelLab";
import { LocalWebhook } from "./LocalWebhook";

export type Fault =
  | "NONE"
  | "CRASH_BEFORE_COMMIT_ONCE"
  | "CRASH_AFTER_COMMIT_ONCE"
  | "ALWAYS_FAIL";
type Control = {
  tick: number;
  running: boolean;
  pending: number;
  processing: number;
  retry: number;
  delivered: number;
  dead: number;
  effects: number;
};
type DeliveryEvent = {
  eventId: string;
  paymentId: string;
  eventType: string;
  status: string;
  fault: Fault;
  attempts: number;
  nextTick: number;
  leaseUntilTick: number | null;
  effects: number;
  duplicatesSuppressed: number;
  payload: Record<string, unknown>;
  createdAt: string;
};
type EventPage = { items: DeliveryEvent[]; total: number };
type Detail = {
  event: DeliveryEvent;
  timeline: {
    sequence: number;
    tick: number;
    attempt: number;
    kind: string;
    detail: string;
    createdAt: string;
  }[];
};
type StepResult = {
  tick: number;
  eventId: string | null;
  outcome: string;
  detail: string;
};
const scenarios: {
  fault: Fault;
  title: string;
  description: string;
  label: string;
}[] = [
  {
    fault: "CRASH_AFTER_COMMIT_ONCE",
    title: "The missing acknowledgement",
    description:
      "Commit a notification, then interrupt before acknowledgement. Recovery must suppress the duplicate.",
    label: "Crash after commit",
  },
  {
    fault: "CRASH_BEFORE_COMMIT_ONCE",
    title: "The unfinished transaction",
    description:
      "Interrupt before the consumer commit. Both inbox and notification must roll back before recovery.",
    label: "Crash before commit",
  },
  {
    fault: "ALWAYS_FAIL",
    title: "The failing consumer",
    description:
      "Fail every attempt, follow exponential backoff, then inspect and redeliver the dead letter.",
    label: "Retry exhaustion",
  },
  {
    fault: "NONE",
    title: "The healthy path",
    description:
      "Commit a notification and acknowledge delivery. Redeliver it to inspect inbox deduplication.",
    label: "Normal delivery",
  },
];
export function SystemsLab({
  token,
  inspectPayment,
}: {
  token: string;
  inspectPayment: (id: string) => void;
}) {
  const cache = useQueryClient();
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [advance, setAdvance] = useState(1);
  const [result, setResult] = useState<StepResult | null>(null);
  const intent = useRef<{ fault: Fault; key: string } | null>(null);
  const control = useQuery({
    queryKey: ["worker"],
    queryFn: () => api<Control>(token, "/worker"),
    refetchInterval: 1500,
    retry: false,
  });
  const queue = useQuery({
    queryKey: ["events", page],
    queryFn: () => api<EventPage>(token, `/events?page=${page}&size=10`),
    refetchInterval: 1500,
    retry: false,
  });
  const detail = useQuery({
    queryKey: ["delivery", selected],
    queryFn: () => api<Detail>(token, `/events/${selected}`),
    enabled: !!selected,
    refetchInterval: 1500,
    retry: false,
  });
  const refresh = () => cache.invalidateQueries();
  const create = useMutation({
    mutationFn: (fault: Fault) => {
      if (!intent.current || intent.current.fault !== fault)
        intent.current = { fault, key: crypto.randomUUID() };
      return api<{ eventId: string }>(token, "/experiments", {
        method: "POST",
        body: { scenario: fault },
        key: intent.current.key,
      });
    },
    onSuccess: (r) => {
      intent.current = null;
      setSelected(r.eventId);
      setPage(0);
      setResult(null);
      void refresh();
    },
  });
  const run = useMutation({
    mutationFn: (running: boolean) =>
      api<Control>(token, "/worker", { method: "PUT", body: { running } }),
    onSuccess: () => void refresh(),
  });
  const step = useMutation({
    mutationFn: () =>
      api<StepResult>(token, "/worker/step", {
        method: "POST",
        body: { eventId: selected, advanceTicks: advance },
      }),
    onSuccess: (r) => {
      setResult(r);
      void refresh();
    },
  });
  const redeliver = useMutation({
    mutationFn: () =>
      api<Detail>(token, `/events/${selected}/redeliver`, { method: "POST" }),
    onSuccess: () => {
      setResult(null);
      void refresh();
    },
  });
  const state = control.data;
  const event = detail.data?.event;
  const busy =
    create.isPending || run.isPending || step.isPending || redeliver.isPending;
  return (
    <div className="systems-lab">
      <Notice
        error={
          control.error ||
          queue.error ||
          detail.error ||
          create.error ||
          run.error ||
          step.error ||
          redeliver.error
        }
      />
      <div className="worker-bar">
        <div>
          <span
            className={state?.running ? "worker-dot running-dot" : "worker-dot"}
          />
          <strong>
            {state
              ? state.running
                ? "Worker running"
                : "Worker paused"
              : "Loading worker…"}
          </strong>
          <span className="clock-chip">
            <Clock3 size={14} />
            Tick {state?.tick ?? "—"}
          </span>
        </div>
        <button
          className="secondary"
          disabled={busy || !state}
          onClick={() => run.mutate(!state?.running)}
        >
          {state?.running ? <Pause size={15} /> : <Play size={15} />}{" "}
          {state?.running ? "Pause worker" : "Run worker"}
        </button>
      </div>
      <div className="systems-stats">
        <Stat
          title="Waiting / retrying"
          value={state ? state.pending + state.retry : "—"}
          caption="Durable events awaiting delivery"
          icon={Layers3}
        />
        <Stat
          title="Consumer effects"
          value={state?.effects ?? "—"}
          caption="Actual synthetic notifications"
          icon={Database}
        />
        <Stat
          title="Dead letters"
          value={state?.dead ?? "—"}
          caption="Three attempts exhausted"
          icon={Activity}
        />
      </div>
      <section aria-label="Failure scenarios" className="scenario-grid">
        {scenarios.map((s) => (
          <article className="panel scenario-card" key={s.fault}>
            <span className="mini-label">{s.label}</span>
            <h2>{s.title}</h2>
            <p>{s.description}</p>
            <button
              className="secondary"
              disabled={busy || !state || state.running}
              onClick={() => create.mutate(s.fault)}
            >
              Prepare {s.label.toLowerCase()}
              <ArrowRight size={14} />
            </button>
          </article>
        ))}
      </section>
      <div className="simulation-note">
        <FlaskConical size={18} />
        <p>
          <strong>Real persistence. Controlled interruptions.</strong> Each
          scenario creates a new synthetic authorization and outbox event.
          Logical ticks drive retries and leases; the background worker advances
          one tick per poll. Crash injection skips a commit or acknowledgement
          boundary; it does not terminate the JVM. No webhook leaves this
          computer.
        </p>
      </div>
      <div className="delivery-grid">
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>
                Delivery queue{" "}
                <span className="count">{queue.data?.total ?? "—"}</span>
              </h2>
              <p>PostgreSQL outbox · One row per committed payment event</p>
            </div>
          </div>
          {queue.isPending ? (
            <div className="empty">Loading events…</div>
          ) : queue.data?.items.length ? (
            <div
              className="table-scroll"
              role="region"
              aria-label="Delivery queue table"
              tabIndex={0}
            >
              <table>
                <thead>
                  <tr>
                    <th>EVENT</th>
                    <th>DELIVERY</th>
                    <th>ATTEMPTS</th>
                    <th>EFFECTS</th>
                  </tr>
                </thead>
                <tbody>
                  {queue.data.items.map((e) => (
                    <tr
                      key={e.eventId}
                      className={selected === e.eventId ? "selected-row" : ""}
                    >
                      <td>
                        <button
                          className="transaction-link"
                          aria-label={`Inspect event ${e.eventId}`}
                          onClick={() => {
                            setSelected(e.eventId);
                            setResult(null);
                          }}
                        >
                          <span>
                            <strong>{e.eventId.slice(0, 8)}…</strong>
                            <small>{e.eventType.toLowerCase()}</small>
                          </span>
                        </button>
                      </td>
                      <td>
                        <Status value={e.status} />
                      </td>
                      <td className="mono">{e.attempts} / 3</td>
                      <td className="mono">{e.effects}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="empty">
              <Layers3 size={30} />
              <h3>No events yet.</h3>
              <p>Prepare a scenario above, or create a new payment.</p>
            </div>
          )}
          <div className="table-footer">
            <span>Page {page + 1} · 10 events per page</span>
            <div>
              <button
                className="secondary"
                disabled={page === 0}
                onClick={() => setPage((p) => p - 1)}
              >
                Previous
              </button>
              <button
                className="secondary"
                disabled={!queue.data || (page + 1) * 10 >= queue.data.total}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </button>
            </div>
          </div>
        </section>
        <section
          className="panel delivery-evidence"
          aria-label="Delivery evidence"
        >
          <div className="panel-heading">
            <div>
              <h2>
                <Activity size={16} /> Delivery evidence
              </h2>
              <p>
                {event
                  ? event.eventId
                  : "Choose an event to inspect its recovery."}
              </p>
            </div>
          </div>
          {detail.isPending && selected ? (
            <p className="field-help">Loading evidence…</p>
          ) : event ? (
            <div className="evidence-content">
              <div className="evidence-state">
                <Status value={event.status} />
                <span className="mini-label">
                  {event.fault.replaceAll("_", " ")}
                </span>
              </div>
              <div className="detail-facts">
                <div>
                  <span>Effects committed</span>
                  <strong data-testid="event-effects">{event.effects}</strong>
                </div>
                <div>
                  <span>Duplicates suppressed</span>
                  <strong data-testid="event-duplicates">
                    {event.duplicatesSuppressed}
                  </strong>
                </div>
                <div>
                  <span>Attempts</span>
                  <strong>{event.attempts} / 3</strong>
                </div>
              </div>
              <div className="delivery-controls">
                <label htmlFor="ticks">Advance logical clock</label>
                <select
                  id="ticks"
                  value={advance}
                  disabled={busy || state?.running}
                  onChange={(e) => setAdvance(Number(e.target.value))}
                >
                  <option value={1}>1 tick</option>
                  <option value={2}>2 ticks · lease recovery</option>
                  <option value={4}>4 ticks</option>
                </select>
                <button
                  className="primary"
                  disabled={busy || !state || state.running}
                  onClick={() => step.mutate()}
                >
                  <SkipForward size={15} />
                  {step.isPending ? "Stepping…" : "Step selected event"}
                </button>
              </div>
              <p className="field-help">
                {event.status === "PROCESSING"
                  ? `Lease expires at tick ${event.leaseUntilTick}.`
                  : event.status === "RETRY"
                    ? `Retry due at tick ${event.nextTick}.`
                    : "Three attempts maximum per delivery cycle. Pause to prepare or step."}
              </p>
              {result && (
                <div className="step-result" role="status">
                  <CheckCircle2 size={17} />
                  <div>
                    <strong>
                      {result.outcome.replaceAll("_", " ").toLowerCase()} · tick{" "}
                      {result.tick}
                    </strong>
                    <p>{result.detail}</p>
                  </div>
                </div>
              )}
              <div className="action-row">
                <button
                  className="secondary"
                  disabled={
                    busy ||
                    state?.running ||
                    !["DELIVERED", "DEAD"].includes(event.status)
                  }
                  onClick={() => redeliver.mutate()}
                >
                  <RefreshCw size={14} />
                  Redeliver event
                </button>
                <button
                  className="text-button"
                  onClick={() => inspectPayment(event.paymentId)}
                >
                  Inspect payment
                  <ArrowRight size={14} />
                </button>
              </div>
              <h3 className="detail-heading">
                Persisted timeline{" "}
                <span className="count">{detail.data?.timeline.length}</span>
              </h3>
              <ol className="timeline delivery-timeline">
                {detail.data?.timeline.length ? (
                  detail.data.timeline.map((item) => (
                    <li key={item.sequence}>
                      <span className="timeline-node" />
                      <div>
                        <strong>
                          {item.kind.replaceAll("_", " ").toLowerCase()}
                        </strong>
                        <time>
                          Tick {item.tick} · Attempt {item.attempt} ·{" "}
                          {timestamp(item.createdAt)}
                        </time>
                        <p>{item.detail}</p>
                      </div>
                    </li>
                  ))
                ) : (
                  <li>
                    <p className="field-help">
                      No delivery attempts. Step to claim this event.
                    </p>
                  </li>
                )}
              </ol>
              <p className="field-help">
                Most recent 100 timeline records. Delivery may be out of order;
                the sink archives events rather than overwriting payment state.
              </p>
              <details className="event-payload">
                <summary>Immutable event envelope</summary>
                <pre>{JSON.stringify(event.payload, null, 2)}</pre>
              </details>
              <LocalWebhook
                key={event.eventId}
                token={token}
                eventId={event.eventId}
              />
            </div>
          ) : (
            <div className="empty">
              <Activity size={28} />
              <h3>Follow the evidence.</h3>
              <p>
                Inspect a queued event or prepare a controlled failure scenario.
              </p>
            </div>
          )}
        </section>
      </div>
      <ModelLab token={token} />
    </div>
  );
}
