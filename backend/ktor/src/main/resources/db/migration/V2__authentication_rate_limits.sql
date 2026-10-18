-- Persistent, shared counters. Keys are HMACs, never usernames, IPs or credentials.
CREATE TABLE auditlab.auth_rate_limits (
    bucket_key char(64) PRIMARY KEY CHECK (bucket_key ~ '^[0-9a-f]{64}$'),
    window_started timestamptz NOT NULL,
    attempt_count integer NOT NULL CHECK (attempt_count > 0)
);
CREATE INDEX auth_rate_expiry_idx ON auditlab.auth_rate_limits(window_started);
REVOKE ALL ON auditlab.auth_rate_limits FROM PUBLIC;
