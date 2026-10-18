-- The migration account owns this schema. Runtime grants are applied explicitly by CLI.
REVOKE ALL ON SCHEMA auditlab FROM PUBLIC;

CREATE TABLE auditlab.users (
    id uuid PRIMARY KEY,
    username varchar(64) NOT NULL CHECK (username ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{2,63}$'),
    password_hash varchar(512) NOT NULL CHECK (password_hash LIKE '$argon2id$%'),
    role varchar(16) NOT NULL CHECK (role IN ('admin', 'auditor')),
    status varchar(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_username_unique ON auditlab.users (lower(username));

CREATE TABLE auditlab.user_sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auditlab.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CHECK (expires_at > created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE INDEX sessions_user_idx ON auditlab.user_sessions(user_id);
CREATE INDEX sessions_expiry_idx ON auditlab.user_sessions(expires_at);

CREATE TABLE auditlab.refresh_tokens (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES auditlab.user_sessions(id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    parent_token_id uuid UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    revoked_at timestamptz,
    UNIQUE (id, session_id),
    FOREIGN KEY (parent_token_id, session_id) REFERENCES auditlab.refresh_tokens(id, session_id),
    CHECK (parent_token_id IS NULL OR parent_token_id <> id),
    CHECK (expires_at > created_at),
    CHECK (used_at IS NULL OR used_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE INDEX refresh_session_idx ON auditlab.refresh_tokens(session_id);

CREATE TABLE auditlab.enrollment_codes (
    id uuid PRIMARY KEY,
    code_hash char(64) NOT NULL UNIQUE CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    created_by uuid NOT NULL REFERENCES auditlab.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    consumed_device_id uuid UNIQUE,
    UNIQUE (consumed_device_id, id),
    CHECK (expires_at > created_at),
    CHECK ((used_at IS NULL AND consumed_device_id IS NULL) OR
           (used_at IS NOT NULL AND used_at >= created_at AND consumed_device_id IS NOT NULL))
);
CREATE INDEX enrollment_expiry_idx ON auditlab.enrollment_codes(expires_at);
CREATE INDEX enrollment_creator_idx ON auditlab.enrollment_codes(created_by);

CREATE TABLE auditlab.devices (
    id uuid PRIMARY KEY,
    enrollment_code_id uuid NOT NULL UNIQUE REFERENCES auditlab.enrollment_codes(id),
    token_hash char(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    consent_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked')),
    app_version varchar(40),
    enrolled_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz,
    revoked_at timestamptz,
    UNIQUE (enrollment_code_id, id),
    CHECK (app_version IS NULL OR length(app_version) > 0),
    CHECK ((status = 'active' AND revoked_at IS NULL) OR
           (status = 'revoked' AND revoked_at IS NOT NULL AND revoked_at >= enrolled_at))
);
CREATE INDEX devices_history_idx ON auditlab.devices(enrolled_at DESC, id DESC);
CREATE INDEX devices_status_idx ON auditlab.devices(status, enrolled_at DESC, id DESC);
ALTER TABLE auditlab.enrollment_codes ADD CONSTRAINT enrollment_consumed_device_fk
    FOREIGN KEY (id, consumed_device_id) REFERENCES auditlab.devices(enrollment_code_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE auditlab.devices ADD CONSTRAINT device_consumed_code_fk
    FOREIGN KEY (id, enrollment_code_id) REFERENCES auditlab.enrollment_codes(consumed_device_id, id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE auditlab.consents (
    id uuid PRIMARY KEY,
    device_id uuid NOT NULL UNIQUE REFERENCES auditlab.devices(id),
    policy_version varchar(40) NOT NULL CHECK (policy_version ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,39}$'),
    accepted_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    UNIQUE (id, device_id),
    CHECK (revoked_at IS NULL OR revoked_at >= received_at)
);
ALTER TABLE auditlab.devices ADD CONSTRAINT device_consent_fk
    FOREIGN KEY (consent_id, id) REFERENCES auditlab.consents(id, device_id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE auditlab.events (
    device_id uuid NOT NULL,
    event_id uuid NOT NULL,
    consent_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    category varchar(24) NOT NULL CHECK (category IN ('app_lifecycle', 'sync', 'device_status')),
    direction varchar(16) NOT NULL CHECK (direction IN ('inbound', 'outbound', 'occurred')),
    is_simulated boolean NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}',
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (device_id, event_id),
    FOREIGN KEY (consent_id, device_id) REFERENCES auditlab.consents(id, device_id),
    CHECK ((CASE WHEN jsonb_typeof(metadata) = 'object' THEN
        metadata - ARRAY['networkState','batteryPercent','appEventCode']::text[] = '{}'::jsonb
        AND (NOT metadata ? 'networkState' OR
             (jsonb_typeof(metadata->'networkState') = 'string' AND metadata->>'networkState' IN ('online','offline','unknown')))
        AND (NOT metadata ? 'appEventCode' OR
             (jsonb_typeof(metadata->'appEventCode') = 'string' AND metadata->>'appEventCode' IN ('agent_started','sync_succeeded','sync_failed','consent_revoked')))
        AND (NOT metadata ? 'batteryPercent' OR CASE WHEN jsonb_typeof(metadata->'batteryPercent') = 'number' THEN
             (metadata->>'batteryPercent')::numeric BETWEEN 0 AND 100
             AND trunc((metadata->>'batteryPercent')::numeric) = (metadata->>'batteryPercent')::numeric
             ELSE false END)
        ELSE false END) IS TRUE)
);
CREATE INDEX events_history_idx ON auditlab.events(occurred_at DESC, event_id DESC, device_id DESC);
CREATE INDEX events_device_history_idx ON auditlab.events(device_id, occurred_at DESC, event_id DESC);
CREATE INDEX events_category_history_idx ON auditlab.events(category, occurred_at DESC, event_id DESC, device_id DESC);
CREATE INDEX events_retention_idx ON auditlab.events(received_at);
CREATE INDEX events_app_code_idx ON auditlab.events((metadata->>'appEventCode'), occurred_at DESC);

CREATE TABLE auditlab.audit_log (
    id uuid PRIMARY KEY,
    actor_type varchar(16) NOT NULL CHECK (actor_type IN ('user','device','system')),
    actor_id uuid,
    action varchar(40) NOT NULL CHECK (action IN (
        'login_succeeded','login_failed','session_refreshed','session_revoked','enrollment_code_created',
        'device_enrolled','devices_listed','device_queried','events_received','events_queried','event_queried','report_exported',
        'device_revoked','consent_revoked','audit_log_queried','retention_executed','bootstrap_user_created')),
    resource jsonb NOT NULL,
    filters jsonb NOT NULL DEFAULT '{}',
    result varchar(16) NOT NULL CHECK (result IN ('success','denied','failure')),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    correlation_id uuid NOT NULL,
    CHECK ((actor_type = 'system' AND actor_id IS NULL) OR (actor_type <> 'system' AND actor_id IS NOT NULL)),
    CHECK ((CASE WHEN jsonb_typeof(resource) = 'object' THEN
        resource - ARRAY['type','deviceId','eventId','userId','sessionId']::text[] = '{}'::jsonb
        AND resource->>'type' IN ('session','device','event','report','audit','enrollment_code','user','system')
        ELSE false END) IS TRUE),
    CHECK ((CASE WHEN jsonb_typeof(filters) = 'object' THEN
        filters - ARRAY['device','category','from','to','q','status','limit','actorId','action','result']::text[] = '{}'::jsonb
        ELSE false END) IS TRUE)
);
CREATE INDEX audit_history_idx ON auditlab.audit_log(occurred_at DESC, id DESC);
CREATE INDEX audit_actor_idx ON auditlab.audit_log(actor_id, occurred_at DESC, id DESC);
CREATE INDEX audit_action_idx ON auditlab.audit_log(action, occurred_at DESC, id DESC);

REVOKE ALL ON ALL TABLES IN SCHEMA auditlab FROM PUBLIC;
