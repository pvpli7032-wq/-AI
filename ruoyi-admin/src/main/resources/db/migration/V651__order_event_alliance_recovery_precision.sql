-- Alliance commissions retain four decimal places. Expand without altering historical event values.
-- Missing recovery remains NULL; never backfill from gross refund or an unconfirmed upstream field.
ALTER TABLE cps_order_event MODIFY COLUMN recovered_amount DECIMAL(14,4) NULL COMMENT '累计联盟佣金追回额，缺证据留空';