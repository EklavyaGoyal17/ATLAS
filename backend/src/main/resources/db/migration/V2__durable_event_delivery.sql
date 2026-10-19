-- Applies to new events only. Historical audit snapshots cannot be reconstructed honestly.
CREATE TABLE worker_control (
    id integer PRIMARY KEY CHECK (id=1),
    current_tick bigint NOT NULL DEFAULT 0 CHECK (current_tick >= 0),
    running boolean NOT NULL DEFAULT false
);
INSERT INTO worker_control(id) VALUES (1);

CREATE TABLE outbox_event (
    event_id uuid PRIMARY KEY REFERENCES audit_event(id),
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    payment_id uuid NOT NULL REFERENCES payment(id),
    event_type varchar(40) NOT NULL,
    payload_json text NOT NULL CHECK (jsonb_typeof(payload_json::jsonb)='object'),
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSING','RETRY','DELIVERED','DEAD')),
    fault varchar(40) NOT NULL DEFAULT 'NONE' CHECK (fault IN ('NONE','CRASH_BEFORE_COMMIT_ONCE','CRASH_AFTER_COMMIT_ONCE','ALWAYS_FAIL')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    next_tick bigint NOT NULL DEFAULT 0 CHECK (next_tick >= 0),
    lease_token uuid,
    lease_until_tick bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status='PROCESSING' AND lease_token IS NOT NULL AND lease_until_tick IS NOT NULL)
        OR (status<>'PROCESSING' AND lease_token IS NULL AND lease_until_tick IS NULL))
);
CREATE INDEX outbox_ready ON outbox_event(status,next_tick,sequence);
CREATE TABLE consumer_inbox (
    event_id uuid PRIMARY KEY REFERENCES outbox_event(event_id),
    first_attempt integer NOT NULL CHECK (first_attempt BETWEEN 1 AND 3),
    processed_tick bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
-- The synthetic sink is a local notification archive, not an external webhook.
CREATE TABLE notification (
    event_id uuid PRIMARY KEY REFERENCES consumer_inbox(event_id),
    payment_id uuid NOT NULL REFERENCES payment(id),
    payload_json text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE delivery_log (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES outbox_event(event_id),
    tick bigint NOT NULL,
    attempt integer NOT NULL CHECK (attempt BETWEEN 0 AND 3),
    kind varchar(40) NOT NULL,
    detail varchar(240) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX delivery_timeline ON delivery_log(event_id,sequence);
CREATE TABLE experiment (
    id uuid PRIMARY KEY,
    request_key varchar(100) NOT NULL UNIQUE,
    event_id uuid NOT NULL UNIQUE REFERENCES outbox_event(event_id),
    scenario varchar(40) NOT NULL CHECK (scenario IN ('NONE','CRASH_BEFORE_COMMIT_ONCE','CRASH_AFTER_COMMIT_ONCE','ALWAYS_FAIL')),
    created_at timestamptz NOT NULL DEFAULT now()
);
