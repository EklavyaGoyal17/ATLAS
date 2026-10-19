CREATE TABLE merchant (
    id varchar(64) PRIMARY KEY,
    name varchar(100) NOT NULL
);
INSERT INTO merchant VALUES ('merchant_demo', 'Orbit Supply · Synthetic merchant');

CREATE TABLE payment (
    id uuid PRIMARY KEY,
    merchant_id varchar(64) NOT NULL REFERENCES merchant(id),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 1000000000000),
    currency varchar(3) NOT NULL CHECK (currency IN ('USD','EUR','INR')),
    status varchar(16) NOT NULL CHECK (status IN ('AUTHORIZED','DECLINED','CAPTURED','REFUNDED','REVERSED')),
    captured_minor bigint NOT NULL DEFAULT 0,
    refunded_minor bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (0 <= refunded_minor AND refunded_minor <= captured_minor AND captured_minor <= amount_minor),
    CHECK ((status IN ('AUTHORIZED','DECLINED','REVERSED') AND captured_minor = 0 AND refunded_minor = 0)
        OR (status = 'CAPTURED' AND captured_minor = amount_minor AND refunded_minor < captured_minor)
        OR (status = 'REFUNDED' AND captured_minor = amount_minor AND refunded_minor = captured_minor))
);
CREATE INDEX payment_history ON payment(created_at DESC, id);

-- Each row is an indivisible balanced posting. No API can insert one unbalanced side.
CREATE TABLE ledger_posting (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES payment(id),
    kind varchar(16) NOT NULL CHECK (kind IN ('CAPTURE','REFUND')),
    debit_account varchar(64) NOT NULL,
    credit_account varchar(64) NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    currency varchar(3) NOT NULL CHECK (currency IN ('USD','EUR','INR')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (debit_account <> credit_account)
);
CREATE UNIQUE INDEX one_capture_per_payment ON ledger_posting(payment_id) WHERE kind = 'CAPTURE';
CREATE INDEX ledger_payment ON ledger_posting(payment_id);

CREATE TABLE audit_event (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id uuid NOT NULL UNIQUE,
    payment_id uuid NOT NULL REFERENCES payment(id),
    type varchar(40) NOT NULL,
    detail varchar(240) NOT NULL,
    correlation_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_payment ON audit_event(payment_id,sequence);

CREATE TABLE idempotency_record (
    merchant_id varchar(64) NOT NULL REFERENCES merchant(id),
    request_key varchar(100) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    response_json text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(merchant_id, request_key)
);

CREATE VIEW ledger_entry AS
    SELECT id, payment_id, currency, debit_account AS account, amount_minor AS debit_minor, 0::bigint AS credit_minor FROM ledger_posting
    UNION ALL
    SELECT id, payment_id, currency, credit_account AS account, 0::bigint AS debit_minor, amount_minor AS credit_minor FROM ledger_posting;
