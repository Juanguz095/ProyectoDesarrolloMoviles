-- Additive: explicit gallery batches shared by the user (plan 0.9, M2, D11/D12).
-- PostgreSQL keeps metadata and references only; the files live on the server filesystem.
CREATE TABLE auditlab.gallery_batches (
    id uuid PRIMARY KEY,
    device_id uuid NOT NULL REFERENCES auditlab.devices(id),
    consent_version varchar(32) NOT NULL CHECK (consent_version = 'gallery-share-1'),
    item_count integer NOT NULL CHECK (item_count BETWEEN 1 AND 20),
    total_bytes bigint NOT NULL CHECK (total_bytes > 0 AND total_bytes <= 157286400),
    state varchar(16) NOT NULL CHECK (state IN ('UPLOADING','READY','DELETED')),
    created_at timestamptz NOT NULL,
    completed_at timestamptz
);
CREATE INDEX gallery_batches_device_idx ON auditlab.gallery_batches(device_id, created_at DESC, id DESC);

CREATE TABLE auditlab.gallery_items (
    batch_id uuid NOT NULL REFERENCES auditlab.gallery_batches(id) ON DELETE CASCADE,
    item_id varchar(64) NOT NULL CHECK (item_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    file_name varchar(120) NOT NULL CHECK (length(file_name) BETWEEN 1 AND 120),
    media_type varchar(32) NOT NULL CHECK (media_type IN ('image/jpeg','image/png','image/webp','image/heif','image/heic')),
    size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 15728640),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    state varchar(16) NOT NULL CHECK (state IN ('PENDING','UPLOADED')) DEFAULT 'PENDING',
    PRIMARY KEY (batch_id, item_id)
);

ALTER TABLE auditlab.audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE auditlab.audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'login_succeeded','login_failed','session_refreshed','session_revoked','enrollment_code_created',
    'device_enrolled','devices_listed','device_queried','events_received','events_queried','event_queried',
    'report_exported','device_revoked','consent_revoked','audit_log_queried','retention_executed','bootstrap_user_created',
    'screen_frame_queried','whatsapp_sharing_started','whatsapp_sharing_stopped','whatsapp_previews_queried',
    'app_releases_read','app_update_requested','app_update_read','app_update_result',
    'consent_module_updated','gallery_batch_shared','gallery_batches_queried','gallery_item_downloaded','gallery_batch_deleted'
));
