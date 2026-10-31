-- Match the retry selection order without changing event identity or stored attempts.
ALTER TABLE cps_attribution_settlement_outbox
 ADD KEY idx_attribution_retry_pending(status,attempts,id);
