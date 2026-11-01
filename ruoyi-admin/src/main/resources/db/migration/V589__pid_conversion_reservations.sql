-- A lease may be shared by concurrent conversions of the same user and item.
-- It is recycled only after every in-flight conversion failed and none succeeded.
CREATE TABLE cps_pid_conversion_state (
    lease_id BIGINT NOT NULL PRIMARY KEY,
    published BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB;
CREATE TABLE cps_pid_conversion_attempt (
    attempt_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    lease_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_pid_conversion_attempt_lease (lease_id)
) ENGINE=InnoDB;
