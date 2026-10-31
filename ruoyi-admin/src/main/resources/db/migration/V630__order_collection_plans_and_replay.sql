-- All times in these planning tables are UTC. No real account/PID or implicit production policy.
CREATE TABLE cps_order_collection_checkpoint (
    platform VARCHAR(20) NOT NULL,
    task_type VARCHAR(20) NOT NULL,
    cycle_key VARCHAR(20) NOT NULL,
    checkpoint_to DATETIME(3) NOT NULL,
    PRIMARY KEY(platform, task_type, cycle_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_order_collection_plan (
    run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    platform VARCHAR(20) NOT NULL,
    task_type VARCHAR(20) NOT NULL,
    cycle_key VARCHAR(20) NOT NULL,
    expected_to DATETIME(3) NOT NULL,
    window_from DATETIME(3) NOT NULL,
    window_to DATETIME(3) NOT NULL,
    KEY idx_order_plan_cycle(platform, task_type, cycle_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_order_collection_rejection (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    platform VARCHAR(20) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_order_rejection_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Append-only normalized replay envelope, linked to the separately retained exact response text.
CREATE TABLE cps_order_replay_input (
    raw_id BIGINT NOT NULL PRIMARY KEY,
    input_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '订单采集策略（上线前配置）','cps.order.collection.policy','{"enabled":false}', 'Y','system',NOW(),
       '默认关闭；窗口、重叠、回补周期、重试、出箱和PID/siteId白名单全部显式配置，不依赖代理申请'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='cps.order.collection.policy');
