CREATE TABLE workspace_document (
  id uuid PRIMARY KEY,
  kind varchar(24) NOT NULL CHECK (kind IN ('DIAGRAM','ADR','JOURNAL','LEARNING','POSTMORTEM','REVIEW','INCIDENT')),
  title varchar(120) NOT NULL,
  version integer NOT NULL CHECK (version > 0),
  body_json text NOT NULL CHECK (octet_length(body_json) <= 12000),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE document_revision (
  document_id uuid NOT NULL REFERENCES workspace_document(id) ON DELETE CASCADE,
  version integer NOT NULL,
  title varchar(120) NOT NULL,
  body_json text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(document_id, version)
);
CREATE TABLE workspace_audit (
  sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  action varchar(32) NOT NULL,
  target varchar(100) NOT NULL,
  correlation_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
