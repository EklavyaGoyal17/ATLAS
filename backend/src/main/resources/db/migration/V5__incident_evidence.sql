CREATE TABLE incident_evidence (
  incident_id uuid PRIMARY KEY REFERENCES workspace_document(id) ON DELETE CASCADE,
  evidence_json text NOT NULL CHECK (octet_length(evidence_json) <= 128000),
  created_at timestamptz NOT NULL DEFAULT now()
);
