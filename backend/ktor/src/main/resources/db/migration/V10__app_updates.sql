-- Additive migration: releases are immutable snapshots from the operator's approved catalog.
CREATE TABLE auditlab.app_update_requests (
    id uuid PRIMARY KEY,
    device_id uuid NOT NULL REFERENCES auditlab.devices(id),
    requested_by uuid NOT NULL REFERENCES auditlab.users(id),
    release_id uuid NOT NULL,
    release_snapshot jsonb NOT NULL CHECK (jsonb_typeof(release_snapshot) = 'object'),
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','installer_opened','installed','declined','failed','expired')),
    requested_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    error_code text CHECK (error_code IN ('DOWNLOAD_FAILED','VERIFICATION_FAILED','INSTALLER_UNAVAILABLE','LOCAL_CANCELLED')),
    CHECK (expires_at > requested_at),
    CHECK (error_code IS NULL OR status = 'failed')
);
CREATE UNIQUE INDEX app_update_one_active ON auditlab.app_update_requests(device_id)
    WHERE status IN ('pending','installer_opened');
CREATE INDEX app_update_history ON auditlab.app_update_requests(device_id, requested_at DESC);

ALTER TABLE auditlab.audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE auditlab.audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'login_succeeded','login_failed','session_refreshed','session_revoked','enrollment_code_created',
    'device_enrolled','devices_listed','device_queried','events_received','events_queried','event_queried',
    'report_exported','device_revoked','consent_revoked','audit_log_queried','retention_executed','bootstrap_user_created',
    'screen_frame_queried','whatsapp_sharing_started','whatsapp_sharing_stopped','whatsapp_previews_queried',
    'app_releases_read','app_update_requested','app_update_read','app_update_result'
));
ALTER TABLE auditlab.audit_log DROP CONSTRAINT audit_log_resource_check;
ALTER TABLE auditlab.audit_log ADD CONSTRAINT audit_log_resource_check CHECK ((CASE
    WHEN jsonb_typeof(resource) = 'object' THEN
        (resource - ARRAY['type','deviceId','eventId','userId','sessionId']::text[] = '{}'::jsonb
         AND resource->>'type' IN ('session','device','event','report','audit','enrollment_code','user','system'))
        OR (resource->>'type' = 'whatsapp_preview'
         AND resource - ARRAY['type','deviceId','sessionId','consentId','acceptedAt','policyVersion']::text[] = '{}'::jsonb)
        OR (resource->>'type' IN ('app_release','app_update')
         AND resource - ARRAY['type','deviceId','releaseId','requestId']::text[] = '{}'::jsonb)
    ELSE false END) IS TRUE);
