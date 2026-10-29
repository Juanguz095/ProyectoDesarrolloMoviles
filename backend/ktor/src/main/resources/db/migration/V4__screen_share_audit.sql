ALTER TABLE auditlab.audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE auditlab.audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'login_succeeded','login_failed','session_refreshed','session_revoked','enrollment_code_created',
    'device_enrolled','devices_listed','device_queried','events_received','events_queried','event_queried',
    'report_exported','device_revoked','consent_revoked','audit_log_queried','retention_executed','bootstrap_user_created',
    'screen_frame_queried'
));
