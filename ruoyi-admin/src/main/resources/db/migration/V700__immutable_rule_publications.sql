-- New rule-owned tables only. No rates or financial defaults are seeded.
-- UTC JDBC/session timezone is required for DATETIME(3) <-> Instant conversion.
CREATE TABLE cps_rule_document (
 id BIGINT NOT NULL AUTO_INCREMENT,
 platform VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL,
 config JSON NOT NULL,
 archived BOOLEAN NOT NULL,
 updated_by BIGINT NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_rule_document_platform(platform),
 CONSTRAINT chk_rule_document_revision CHECK(revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_rule_publication (
 id BIGINT NOT NULL AUTO_INCREMENT,
 document_id BIGINT NOT NULL,
 revision BIGINT NOT NULL,
 config JSON NOT NULL,
 enabled BOOLEAN NOT NULL,
 effective_at DATETIME(3) NOT NULL,
 created_by BIGINT NOT NULL,
 safety_reference VARCHAR(512) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_rule_publication_revision(document_id,revision),
 KEY idx_rule_publication_asof(document_id,effective_at,id),
 CONSTRAINT fk_rule_publication_document FOREIGN KEY(document_id) REFERENCES cps_rule_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_rule_snapshot (
 order_line_id BIGINT NOT NULL,
 buyer_user_id BIGINT NOT NULL,
 version_id BIGINT NOT NULL,
 snapshot JSON NOT NULL,
 PRIMARY KEY(order_line_id),
 CONSTRAINT fk_rule_snapshot_publication FOREIGN KEY(version_id) REFERENCES cps_rule_publication(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
