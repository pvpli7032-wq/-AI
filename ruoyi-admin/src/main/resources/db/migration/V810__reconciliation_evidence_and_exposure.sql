-- Only synthetic configuration placeholders. Finance must set the limit before withdrawals.
ALTER TABLE cps_recon_batch
 ADD COLUMN fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN diff_count INT NOT NULL DEFAULT 0,
 ADD COLUMN created_at DATETIME(3) NULL,
 ADD COLUMN completed_at DATETIME(3) NULL,
 ADD UNIQUE KEY uk_recon_fingerprint(fingerprint);
ALTER TABLE cps_recon_diff
 ADD COLUMN detail VARCHAR(1000) NULL,
 ADD COLUMN resolve_note VARCHAR(500) NULL,
 ADD COLUMN created_at DATETIME(3) NULL,
 ADD COLUMN resolved_at DATETIME(3) NULL;

CREATE TABLE cps_recon_guard (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO cps_recon_guard(id) VALUES(1);

CREATE TABLE cps_recon_source (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 platform VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 external_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 cash_identity VARCHAR(161) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
  GENERATED ALWAYS AS (CASE WHEN source_type IN ('UNION_RECEIPT','PAYOUT')
    THEN CONCAT(source_type,':',external_id) ELSE NULL END) STORED,
 order_no VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
 sub_order_no VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
 withdraw_no VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
 amount DECIMAL(18,4) NOT NULL,
 expected_receipt_amount DECIMAL(14,2) NULL COMMENT '联盟报表列示净应到账，不猜服务费和税费',
 occurred_on DATE NOT NULL,
 operator_id BIGINT NOT NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_recon_source(platform,source_type,external_id),
 UNIQUE KEY uk_recon_cash_identity(cash_identity),
 KEY idx_recon_source_period(platform,occurred_on),
 KEY idx_recon_source_order(platform,order_no,sub_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_recon_coverage (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 allocation_no VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 receipt_id BIGINT NOT NULL,
 amount DECIMAL(14,2) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 operator_id BIGINT NOT NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_recon_coverage_no(allocation_no),
 KEY idx_recon_coverage_receipt(receipt_id),
 CONSTRAINT chk_recon_coverage_amount CHECK(amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_exposure_snapshot (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 platform VARCHAR(32) NOT NULL COMMENT '平台或 UNALLOCATED_USER_DEBT 保守风险桶',
 amount DECIMAL(18,2) NOT NULL,
 observed_at DATETIME(3) NOT NULL,
 KEY idx_exposure_platform_time(platform,observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '垫付敞口上限（待财务配置）','cps.exposure.limit','','N','line12-migration',NOW(),
 '空值/无效值禁止提现；正数为人民币上限；达到上限转人工、超过上限拒绝。无真实业务阈值种子。'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.exposure.limit');
