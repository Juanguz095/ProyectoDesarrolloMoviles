-- Optional, separately authorized phone numbers. Existing rows remain valid.
ALTER TABLE auditlab.events DROP CONSTRAINT events_metadata_check;
ALTER TABLE auditlab.events ADD CONSTRAINT events_metadata_check CHECK ((CASE WHEN jsonb_typeof(metadata)='object' THEN
 metadata - ARRAY['networkState','batteryPercent','appEventCode','phoneNumber']::text[] = '{}'::jsonb
 AND (NOT metadata ? 'networkState' OR (jsonb_typeof(metadata->'networkState')='string' AND metadata->>'networkState' IN ('online','offline','unknown')))
 AND (NOT metadata ? 'batteryPercent' OR CASE WHEN jsonb_typeof(metadata->'batteryPercent')='number' THEN (metadata->>'batteryPercent')::numeric BETWEEN 0 AND 100 AND trunc((metadata->>'batteryPercent')::numeric)=(metadata->>'batteryPercent')::numeric ELSE false END)
 AND (NOT metadata ? 'appEventCode' OR (jsonb_typeof(metadata->'appEventCode')='string' AND metadata->>'appEventCode' IN ('agent_started','sync_succeeded','sync_failed','consent_revoked','sms_received','sms_sent','call_received','call_missed','call_rejected','call_outgoing','device_status_changed','whatsapp_notification_received','whatsapp_reply_observed')))
 AND (NOT metadata ? 'phoneNumber' OR (NOT is_simulated AND category IN ('sms','call') AND jsonb_typeof(metadata->'phoneNumber')='string' AND metadata->>'phoneNumber' ~ '^[+0-9 ()*#.-]{1,40}$'))
 ELSE false END) IS TRUE);
