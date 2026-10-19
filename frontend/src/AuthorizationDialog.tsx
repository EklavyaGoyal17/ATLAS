import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { ArrowRight, FlaskConical } from "lucide-react";
import { api, type Authorization, type Result } from "./api";
import { Modal, Notice } from "./ui";
export function Authorize({
  token,
  close,
  complete,
}: {
  token: string;
  close: () => void;
  complete: (r: Result, b: Authorization, key: string) => void;
}) {
  const [amount, setAmount] = useState("12500");
  const [currency, setCurrency] = useState("USD");
  const [key, setKey] = useState<string>(crypto.randomUUID());
  const [attempted, setAttempted] = useState(false);
  const mutation = useMutation({
    mutationFn: () =>
      api<Result>(token, "/payments", {
        method: "POST",
        key,
        body: {
          merchantId: "merchant_demo",
          amountMinor: Number(amount),
          currency,
        },
      }),
    onSuccess: (r) =>
      complete(
        r,
        { merchantId: "merchant_demo", amountMinor: Number(amount), currency },
        key,
      ),
  });
  return (
    <Modal title="New authorization" close={close}>
      <p className="modal-intro">
        Create a synthetic payment for Orbit Supply. This records an
        authorization; it does not post to the ledger.
      </p>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          setAttempted(true);
          mutation.mutate();
        }}
      >
        <label htmlFor="amount">Amount in minor units</label>
        <input
          autoFocus
          id="amount"
          type="text"
          inputMode="numeric"
          pattern="[0-9]+"
          value={amount}
          disabled={attempted}
          onChange={(e) => setAmount(e.target.value)}
          required
        />
        <p className="field-help">
          12500 = 125.00. Integer between 1 and 1,000,000,000,000.
        </p>
        <label htmlFor="currency">Currency</label>
        <select
          id="currency"
          value={currency}
          disabled={attempted}
          onChange={(e) => setCurrency(e.target.value)}
        >
          <option>USD</option>
          <option>EUR</option>
          <option>INR</option>
        </select>
        <label htmlFor="key">Idempotency key</label>
        <input
          id="key"
          className="mono"
          pattern="[A-Za-z0-9._:\-]{8,100}"
          minLength={8}
          maxLength={100}
          required
          value={key}
          disabled={attempted}
          onChange={(e) => setKey(e.target.value)}
        />
        <p className="field-help">
          The same key and payload return the original response. After an
          attempt, retry with this exact intent or close and start a new one.
        </p>
        <Notice error={mutation.error} />
        <button
          className="primary full"
          disabled={
            mutation.isPending ||
            !/^\d+$/.test(amount) ||
            Number(amount) < 1 ||
            Number(amount) > 1e12
          }
        >
          {mutation.isPending
            ? "Authorizing…"
            : attempted
              ? "Retry same authorization"
              : "Authorize payment"}
          <ArrowRight size={16} />
        </button>
      </form>
      <div className="safe-note">
        <FlaskConical size={14} /> No real money moves.
      </div>
    </Modal>
  );
}
