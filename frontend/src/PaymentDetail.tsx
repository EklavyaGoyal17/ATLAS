import { useState, useRef } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Activity, ArrowDownLeft, Check, Layers3 } from "lucide-react";
import { api, money, timestamp, type Detail, type Result } from "./api";
import { Modal, Notice, Status } from "./ui";
export function PaymentDetail({
  token,
  id,
  close,
  changed,
}: {
  token: string;
  id: string;
  close: () => void;
  changed: () => void;
}) {
  const cache = useQueryClient();
  const [refund, setRefund] = useState("2500");
  const intent = useRef<{ fingerprint: string; key: string } | null>(null);
  const detail = useQuery({
    queryKey: ["detail", id],
    queryFn: () => api<Detail>(token, `/payments/${id}`),
    retry: false,
  });
  const action = useMutation({
    mutationFn: (kind: "capture" | "refund" | "reverse") => {
      const fingerprint = `${id}|${kind}|${refund}`;
      if (!intent.current || intent.current.fingerprint !== fingerprint)
        intent.current = { fingerprint, key: crypto.randomUUID() };
      return api<Result>(token, `/payments/${id}/${kind}`, {
        method: "POST",
        key: intent.current.key,
        ...(kind === "refund" ? { body: { amountMinor: Number(refund) } } : {}),
      });
    },
    onSuccess: () => {
      intent.current = null;
      void cache.invalidateQueries({ queryKey: ["detail", id] });
      changed();
    },
  });
  const p = detail.data?.payment;
  return (
    <Modal title="Transaction investigation" close={close}>
      <Notice error={detail.error || action.error} />
      {detail.isPending ? (
        <p>Loading transaction…</p>
      ) : (
        p && (
          <>
            <div className="detail-summary">
              <Status value={p.status} />
              <h3>{money(p.amountMinor, p.currency)}</h3>
              <code>{p.id}</code>
              <p>
                {timestamp(p.createdAt)} · Version {p.version}
              </p>
            </div>
            <div className="detail-facts">
              <div>
                <span>Captured</span>
                <strong>{money(p.capturedMinor, p.currency)}</strong>
              </div>
              <div>
                <span>Refunded</span>
                <strong>{money(p.refundedMinor, p.currency)}</strong>
              </div>
              <div>
                <span>Refundable</span>
                <strong>
                  {money(p.capturedMinor - p.refundedMinor, p.currency)}
                </strong>
              </div>
            </div>
            {p.status === "AUTHORIZED" && (
              <div className="action-row">
                <button
                  className="primary"
                  disabled={action.isPending}
                  onClick={() => action.mutate("capture")}
                >
                  Capture payment
                  <Check size={15} />
                </button>
                <button
                  className="secondary"
                  disabled={action.isPending}
                  onClick={() => action.mutate("reverse")}
                >
                  Reverse authorization
                </button>
              </div>
            )}
            {p.status === "CAPTURED" && (
              <form
                className="refund-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  action.mutate("refund");
                }}
              >
                <label htmlFor="refund">Refund in minor units</label>
                <div>
                  <input
                    id="refund"
                    inputMode="numeric"
                    pattern="[0-9]+"
                    value={refund}
                    disabled={action.isPending}
                    onChange={(e) => setRefund(e.target.value)}
                    required
                  />
                  <button
                    className="secondary"
                    disabled={
                      action.isPending ||
                      !/^\d+$/.test(refund) ||
                      Number(refund) < 1 ||
                      Number(refund) > p.capturedMinor - p.refundedMinor
                    }
                  >
                    Refund amount
                    <ArrowDownLeft size={15} />
                  </button>
                </div>
              </form>
            )}
            <h3 className="detail-heading">
              <Activity size={16} /> Audit timeline{" "}
              <span className="count">{detail.data?.events.length}</span>
            </h3>
            <ol className="timeline">
              {detail.data?.events.map((e) => (
                <li key={e.sequence}>
                  <span className="timeline-node" />
                  <div>
                    <strong>{e.type.toLowerCase()}</strong>
                    <time>{timestamp(e.createdAt)}</time>
                    <p>{e.detail}</p>
                    <small className="mono">
                      Correlation {e.correlationId}
                    </small>
                  </div>
                </li>
              ))}
            </ol>
            <h3 className="detail-heading">
              <Layers3 size={16} /> Ledger postings
            </h3>
            {detail.data?.postings.length ? (
              detail.data.postings.map((post) => (
                <div className="posting" key={post.id}>
                  <span className="mini-label">
                    {post.kind} · {money(post.amountMinor, post.currency)}
                  </span>
                  <div>
                    <span>DEBIT</span>
                    <code>{post.debitAccount}</code>
                  </div>
                  <div>
                    <span>CREDIT</span>
                    <code>{post.creditAccount}</code>
                  </div>
                </div>
              ))
            ) : (
              <p className="field-help">
                No ledger movement. Capture creates the first paired posting.
              </p>
            )}
          </>
        )
      )}
    </Modal>
  );
}
