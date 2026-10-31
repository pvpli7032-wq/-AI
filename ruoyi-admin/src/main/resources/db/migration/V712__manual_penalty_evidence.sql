-- Manual penalty evidence is immutable; processing projections never edit source imports.
CREATE TABLE cps_settlement_penalty_import (
 id BIGINT NOT NULL AUTO_INCREMENT,
 import_key CHAR(64) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 punish_import_id BIGINT NOT NULL,
 platform VARCHAR(20) NOT NULL,
 statement_period CHAR(7) NULL,
 source_reference VARCHAR(500) NOT NULL,
 payload LONGTEXT NOT NULL,
 operator_id BIGINT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(id), UNIQUE KEY uk_settlement_penalty_import(import_key),
 UNIQUE KEY uk_settlement_penalty_source(punish_import_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_settlement_penalty_line (
 id BIGINT NOT NULL AUTO_INCREMENT,
 import_id BIGINT NOT NULL,
 line_no INT NOT NULL,
 order_line_id BIGINT NOT NULL,
 event_key VARCHAR(128) NOT NULL,
 input_json LONGTEXT NOT NULL,
 event_json LONGTEXT NULL,
 status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
 result_code VARCHAR(100) NULL,
 processed_at DATETIME(3) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_settlement_penalty_line(import_id,line_no),
 UNIQUE KEY uk_settlement_penalty_event(event_key),
 KEY idx_settlement_penalty_pending(import_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
