-- Consent receipts carry only bounded scope metadata. Message title/body are
-- deliberately excluded from the resource and filters allowlists.
ALTER TABLE auditlab.audit_log DROP CONSTRAINT audit_log_resource_check;
ALTER TABLE auditlab.audit_log ADD CONSTRAINT audit_log_resource_check CHECK ((CASE
    WHEN jsonb_typeof(resource) = 'object' THEN
        (resource - ARRAY['type','deviceId','eventId','userId','sessionId']::text[] = '{}'::jsonb
         AND resource->>'type' IN ('session','device','event','report','audit','enrollment_code','user','system'))
        OR
        (resource->>'type' = 'whatsapp_preview'
         AND resource - ARRAY['type','deviceId','sessionId','consentId','acceptedAt','policyVersion']::text[] = '{}'::jsonb)
    ELSE false END) IS TRUE);
