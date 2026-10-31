ALTER TABLE cps_attribution_token ADD COLUMN expires_at DATETIME(3) NULL,
    ADD KEY idx_attribution_token_expiry(expires_at);
-- Existing links get the same configured validity window as tracking PID leases.
UPDATE cps_attribution_token t
JOIN sys_config c ON c.config_key='cps.attribution.lease.hours'
SET t.expires_at=TIMESTAMPADD(HOUR, CAST(c.config_value AS UNSIGNED), t.issued_at)
WHERE t.expires_at IS NULL AND c.config_value REGEXP '^[1-9][0-9]*$';
