-- Additive: V1–V10 and all existing event payloads remain unchanged.
ALTER TABLE auditlab.events DROP CONSTRAINT events_category_check;
ALTER TABLE auditlab.events ADD CONSTRAINT events_category_check CHECK (category IN ('app_lifecycle','sync','device_status','sms','call','whatsapp','manual_share'));
ALTER TABLE auditlab.events DROP CONSTRAINT events_metadata_check;
ALTER TABLE auditlab.events ADD CONSTRAINT events_metadata_check CHECK (((category <> 'manual_share' AND ((CASE WHEN jsonb_typeof(metadata)='object' THEN
 metadata - ARRAY['networkState','batteryPercent','appEventCode','phoneNumber','smsText','smsTextTruncated']::text[] = '{}'::jsonb
 AND (NOT metadata ? 'networkState' OR (jsonb_typeof(metadata->'networkState')='string' AND metadata->>'networkState' IN ('online','offline','unknown')))
 AND (NOT metadata ? 'batteryPercent' OR CASE WHEN jsonb_typeof(metadata->'batteryPercent')='number' THEN (metadata->>'batteryPercent')::numeric BETWEEN 0 AND 100 AND trunc((metadata->>'batteryPercent')::numeric)=(metadata->>'batteryPercent')::numeric ELSE false END)
 AND (NOT metadata ? 'appEventCode' OR (jsonb_typeof(metadata->'appEventCode')='string' AND metadata->>'appEventCode' IN ('agent_started','sync_succeeded','sync_failed','consent_revoked','sms_received','sms_sent','call_received','call_missed','call_rejected','call_outgoing','device_status_changed','whatsapp_notification_received','whatsapp_reply_observed')))
 AND (NOT metadata ? 'phoneNumber' OR (NOT is_simulated AND category IN ('sms','call') AND jsonb_typeof(metadata->'phoneNumber')='string' AND metadata->>'phoneNumber' ~ '^[+0-9 ()*#.-]{1,40}$'))
 AND (NOT metadata ? 'smsText' OR (NOT is_simulated AND category='sms' AND jsonb_typeof(metadata->'smsText')='string' AND length(metadata->>'smsText') BETWEEN 1 AND 2000 AND btrim(metadata->>'smsText')<>''))
 AND (NOT metadata ? 'smsTextTruncated' OR (NOT is_simulated AND category='sms' AND metadata ? 'smsText' AND metadata->'smsTextTruncated'='true'::jsonb))
 ELSE false END) IS TRUE)) OR (category='manual_share' AND NOT is_simulated AND direction='occurred'
 AND jsonb_typeof(metadata)='object'
 AND metadata ?& ARRAY['appEventCode','sharedText','shareConsentVersion']
 AND metadata - ARRAY['appEventCode','sharedText','shareConsentVersion']::text[] = '{}'::jsonb
 AND metadata->>'appEventCode'='text_shared_manually'
 AND metadata->>'shareConsentVersion'='manual-share-1'
 AND jsonb_typeof(metadata->'sharedText')='string'
 AND length(metadata->>'sharedText') BETWEEN 1 AND 2000 AND btrim(metadata->>'sharedText')<>'')) IS TRUE);
ALTER TABLE auditlab.events DROP CONSTRAINT events_origin_check;
ALTER TABLE auditlab.events ADD CONSTRAINT events_origin_check CHECK (((category <> 'manual_share' AND (
    (is_simulated AND category NOT IN ('sms','call','whatsapp') AND
        COALESCE(metadata->>'appEventCode','') NOT IN ('sms_received','sms_sent','call_received','call_missed','call_rejected','call_outgoing','device_status_changed','whatsapp_notification_received','whatsapp_reply_observed'))
    OR (NOT is_simulated AND (
        (category='sms' AND direction='inbound' AND metadata->>'appEventCode'='sms_received') OR
        (category='sms' AND direction='outbound' AND metadata->>'appEventCode'='sms_sent') OR
        (category='call' AND direction='inbound' AND metadata->>'appEventCode' IN ('call_received','call_missed','call_rejected')) OR
        (category='call' AND direction='outbound' AND metadata->>'appEventCode'='call_outgoing') OR
        (category='device_status' AND direction='occurred' AND metadata->>'appEventCode'='device_status_changed') OR
        (category='whatsapp' AND direction='inbound' AND metadata->>'appEventCode'='whatsapp_notification_received') OR
        (category='whatsapp' AND direction='outbound' AND metadata->>'appEventCode'='whatsapp_reply_observed')
    )) IS TRUE
)) OR (category='manual_share' AND NOT is_simulated AND direction='occurred'
 AND jsonb_typeof(metadata)='object'
 AND metadata ?& ARRAY['appEventCode','sharedText','shareConsentVersion']
 AND metadata - ARRAY['appEventCode','sharedText','shareConsentVersion']::text[] = '{}'::jsonb
 AND metadata->>'appEventCode'='text_shared_manually'
 AND metadata->>'shareConsentVersion'='manual-share-1'
 AND jsonb_typeof(metadata->'sharedText')='string'
 AND length(metadata->>'sharedText') BETWEEN 1 AND 2000 AND btrim(metadata->>'sharedText')<>'')) IS TRUE);
