CREATE TABLE webhook_receipt (
  event_id uuid PRIMARY KEY REFERENCES outbox_event(event_id) ON DELETE CASCADE,
  payload_sha256 varchar(64) NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now()
);
