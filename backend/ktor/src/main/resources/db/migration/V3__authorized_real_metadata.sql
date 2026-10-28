-- Additive scope change: metadata only. V1/V2 and existing events are preserved.
ALTER TABLE auditlab.events DROP CONSTRAINT events_category_check;
ALTER TABLE auditlab.events ADD CONSTRAINT events_category_check
    CHECK (category IN ('app_lifecycle','sync','device_status','sms','call'));
ALTER TABLE auditlab.events DROP CONSTRAINT events_metadata_check;
ALTER TABLE auditlab.events ADD CONSTRAINT events_metadata_check CHECK (
    (CASE WHEN jsonb_typeof(metadata) = 'object' THEN
        metadata - ARRAY['networkState','batteryPercent','appEventCode']::text[] = '{}'::jsonb
        AND (NOT metadata ? 'networkState' OR
            (jsonb_typeof(metadata->'networkState') = 'string' AND metadata->>'networkState' IN ('online','offline','unknown')))
        AND (NOT metadata ? 'appEventCode' OR
            (jsonb_typeof(metadata->'appEventCode') = 'string' AND metadata->>'appEventCode' IN
                ('agent_started','sync_succeeded','sync_failed','consent_revoked','sms_received','sms_sent',
                 'call_received','call_missed','call_rejected','call_outgoing','device_status_changed')))
        AND (NOT metadata ? 'batteryPercent' OR CASE WHEN jsonb_typeof(metadata->'batteryPercent') = 'number' THEN
            (metadata->>'batteryPercent')::numeric BETWEEN 0 AND 100
            AND trunc((metadata->>'batteryPercent')::numeric) = (metadata->>'batteryPercent')::numeric ELSE false END)
        ELSE false END) IS TRUE);
ALTER TABLE auditlab.events ADD CONSTRAINT events_origin_check CHECK (
    (is_simulated AND category NOT IN ('sms','call') AND
        COALESCE(metadata->>'appEventCode','') NOT IN ('sms_received','sms_sent','call_received','call_missed','call_rejected','call_outgoing','device_status_changed'))
    OR (NOT is_simulated AND (
        (category='sms' AND direction='inbound' AND metadata->>'appEventCode'='sms_received') OR
        (category='sms' AND direction='outbound' AND metadata->>'appEventCode'='sms_sent') OR
        (category='call' AND direction='inbound' AND metadata->>'appEventCode' IN ('call_received','call_missed','call_rejected')) OR
        (category='call' AND direction='outbound' AND metadata->>'appEventCode'='call_outgoing') OR
        (category='device_status' AND direction='occurred' AND metadata->>'appEventCode'='device_status_changed')
    )) IS TRUE
);
