-- Additive: per-function consent modules (plan 0.9, M1). V1–V11 stay unchanged.
-- A device upgraded from 0.8.x has no rows here and must re-accept its modules (D13).
CREATE TABLE auditlab.consent_modules (
    device_id uuid NOT NULL REFERENCES auditlab.devices(id),
    module varchar(32) NOT NULL CHECK (module IN ('base','communications','numbers','sms_text','whatsapp','screen','manual_share','gallery','location','video')),
    policy_version varchar(64) NOT NULL CHECK (length(policy_version) BETWEEN 1 AND 64),
    state varchar(16) NOT NULL CHECK (state IN ('ACCEPTED','PAUSED','REVOKED')),
    accepted_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (device_id, module)
);
CREATE INDEX consent_modules_device_idx ON auditlab.consent_modules(device_id);
ALTER TABLE auditlab.audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE auditlab.audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'login_succeeded','login_failed','session_refreshed','session_revoked','enrollment_code_created',
    'device_enrolled','devices_listed','device_queried','events_received','events_queried','event_queried',
    'report_exported','device_revoked','consent_revoked','audit_log_queried','retention_executed','bootstrap_user_created',
    'screen_frame_queried','whatsapp_sharing_started','whatsapp_sharing_stopped','whatsapp_previews_queried',
    'app_releases_read','app_update_requested','app_update_read','app_update_result',
    'consent_module_updated'
));
