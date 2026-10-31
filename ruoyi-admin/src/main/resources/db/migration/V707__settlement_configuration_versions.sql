-- No production financial defaults. Publish explicit server-reviewed settings via the admin API.
CREATE TABLE cps_settlement_configuration (
  id BIGINT NOT NULL AUTO_INCREMENT,
  configuration_json JSON NOT NULL,
  effective_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT NOT NULL,
  PRIMARY KEY (id),
  KEY idx_settlement_config_effective (effective_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
