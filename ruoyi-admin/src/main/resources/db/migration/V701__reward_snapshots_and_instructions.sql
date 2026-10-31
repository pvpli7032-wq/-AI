-- Reward engine storage. No account/ledger writes; V400 progress table remains untouched.
-- Snapshot payloads are insert-only via RewardProgressMapper; retain them for financial audit.
CREATE TABLE cps_reward_user_lock (
 user_id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
CREATE TABLE cps_reward_order_snapshot (
 order_id BIGINT NOT NULL PRIMARY KEY,
 platform VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 order_no VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 sub_order_no VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 buyer_id BIGINT NOT NULL, inviter_id BIGINT NULL, payload JSON NOT NULL,
 UNIQUE KEY uk_reward_order(platform,order_no,sub_order_no), KEY idx_reward_inviter(inviter_id,buyer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_reward_order_state (
 order_id BIGINT NOT NULL PRIMARY KEY, buyer_id BIGINT NOT NULL,
 qualified BOOLEAN NOT NULL DEFAULT FALSE, valid BOOLEAN NOT NULL DEFAULT FALSE,
 closed BOOLEAN NOT NULL DEFAULT FALSE,
 refund_ratio DECIMAL(19,18) NOT NULL DEFAULT 0,
 baseline_ratio DECIMAL(19,18) NOT NULL DEFAULT 0,
 KEY idx_reward_buyer(buyer_id,qualified,valid),
 CONSTRAINT fk_reward_state_snapshot FOREIGN KEY(order_id) REFERENCES cps_reward_order_snapshot(order_id)
) ENGINE=InnoDB;
CREATE TABLE cps_reward_award (
 id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL, order_id BIGINT NOT NULL, buyer_id BIGINT NOT NULL,
 type VARCHAR(40) NOT NULL, threshold INT NOT NULL, amount DECIMAL(18,2) NOT NULL,
 recovered DECIMAL(18,2) NOT NULL DEFAULT 0,
 KEY idx_reward_award_buyer(buyer_id),
 CONSTRAINT fk_reward_award_snapshot FOREIGN KEY(order_id) REFERENCES cps_reward_order_snapshot(order_id),
 CONSTRAINT chk_reward_award_amount CHECK(amount>0 AND recovered>=0 AND recovered<=amount)
) ENGINE=InnoDB;
-- Durable append-only settlement instructions. Replay all rows for cause_order_id with ledger key
-- deduplication. Never mark an award as paid merely because an instruction exists.
CREATE TABLE cps_reward_instruction (
 sequence_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 business_key VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 cause_order_id BIGINT NOT NULL, payload JSON NOT NULL,
 UNIQUE KEY uk_reward_instruction(business_key), KEY idx_reward_instruction_cause(cause_order_id,sequence_id),
 CONSTRAINT fk_reward_instruction_snapshot FOREIGN KEY(cause_order_id) REFERENCES cps_reward_order_snapshot(order_id)
) ENGINE=InnoDB;
