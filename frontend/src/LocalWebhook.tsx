import { useMutation } from "@tanstack/react-query";
import { api } from "./api";
import { Notice } from "./ui";
export function LocalWebhook({
  token,
  eventId,
}: {
  token: string;
  eventId: string;
}) {
  const send = useMutation({
    mutationFn: () =>
      api<{
        effects: number;
        deliveries: { duplicate: boolean; effectCount: number }[];
        scope: string;
      }>(token, `/webhooks/demo/${eventId}`, { method: "POST" }),
  });
  return (
    <div className="panel padded">
      <h3>Signed webhook delivery</h3>
      <p>
        Send the immutable envelope three times over loopback HTTP. The receiver
        validates its HMAC, timestamp and envelope, then deduplicates by event
        ID.
      </p>
      <Notice error={send.error} />
      <button
        className="secondary"
        disabled={send.isPending}
        onClick={() => send.mutate()}
      >
        Deliver signed local webhook three times
      </button>
      {send.data && (
        <div aria-live="polite">
          <p>{send.data.scope}</p>
          <strong>Receiver effects: {send.data.effects}</strong>
          {send.data.deliveries.map((d, i) => (
            <p key={i}>
              Delivery {i + 1}:{" "}
              {d.duplicate ? "duplicate suppressed" : "first receipt committed"}{" "}
              · effects {d.effectCount}
            </p>
          ))}
        </div>
      )}
    </div>
  );
}
