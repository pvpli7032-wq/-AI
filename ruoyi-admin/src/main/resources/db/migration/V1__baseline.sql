-- V1: all 43 table definitions from sql/cps-202604112202-mt.sql.
-- Keep all original columns, indexes and table options. Commission-rate defaults
-- are zero-valued offline fixtures; no historical users, logs, orders, alliance
-- credentials, promotion IDs or business commission/risk settings are imported.
-- The only account below is the explicit LOCAL TEST admin / LocalTest123! fixture.
-- Do not use this fixture or placeholder SDKs for a public deployment.
SET NAMES utf8mb4;

CREATE TABLE `gen_table` (
  `table_id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `table_name` varchar(200) DEFAULT '' COMMENT '表名称',
  `table_comment` varchar(500) DEFAULT '' COMMENT '表描述',
  `sub_table_name` varchar(64) DEFAULT NULL COMMENT '关联子表的表名',
  `sub_table_fk_name` varchar(64) DEFAULT NULL COMMENT '子表关联的外键名',
  `class_name` varchar(100) DEFAULT '' COMMENT '实体类名称',
  `tpl_category` varchar(200) DEFAULT 'crud' COMMENT '使用的模板（crud单表操作 tree树表操作）',
  `tpl_web_type` varchar(30) DEFAULT '' COMMENT '前端模板类型（element-ui模版 element-plus模版）',
  `package_name` varchar(100) DEFAULT NULL COMMENT '生成包路径',
  `module_name` varchar(30) DEFAULT NULL COMMENT '生成模块名',
  `business_name` varchar(30) DEFAULT NULL COMMENT '生成业务名',
  `function_name` varchar(50) DEFAULT NULL COMMENT '生成功能名',
  `function_author` varchar(50) DEFAULT NULL COMMENT '生成功能作者',
  `gen_type` char(1) DEFAULT '0' COMMENT '生成代码方式（0zip压缩包 1自定义路径）',
  `gen_path` varchar(200) DEFAULT '/' COMMENT '生成路径（不填默认项目路径）',
  `options` varchar(1000) DEFAULT NULL COMMENT '其它生成选项',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`table_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='代码生成业务表';

CREATE TABLE `gen_table_column` (
  `column_id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `table_id` bigint DEFAULT NULL COMMENT '归属表编号',
  `column_name` varchar(200) DEFAULT NULL COMMENT '列名称',
  `column_comment` varchar(500) DEFAULT NULL COMMENT '列描述',
  `column_type` varchar(100) DEFAULT NULL COMMENT '列类型',
  `java_type` varchar(500) DEFAULT NULL COMMENT 'JAVA类型',
  `java_field` varchar(200) DEFAULT NULL COMMENT 'JAVA字段名',
  `is_pk` char(1) DEFAULT NULL COMMENT '是否主键（1是）',
  `is_increment` char(1) DEFAULT NULL COMMENT '是否自增（1是）',
  `is_required` char(1) DEFAULT NULL COMMENT '是否必填（1是）',
  `is_insert` char(1) DEFAULT NULL COMMENT '是否为插入字段（1是）',
  `is_edit` char(1) DEFAULT NULL COMMENT '是否编辑字段（1是）',
  `is_list` char(1) DEFAULT NULL COMMENT '是否列表字段（1是）',
  `is_query` char(1) DEFAULT NULL COMMENT '是否查询字段（1是）',
  `query_type` varchar(200) DEFAULT 'EQ' COMMENT '查询方式（等于、不等于、大于、小于、范围）',
  `html_type` varchar(200) DEFAULT NULL COMMENT '显示类型（文本框、文本域、下拉框、复选框、单选框、日期控件）',
  `dict_type` varchar(200) DEFAULT '' COMMENT '字典类型',
  `sort` int DEFAULT NULL COMMENT '排序',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`column_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='代码生成业务表字段';

CREATE TABLE `magic_api_backup` (
  `id` varchar(32) NOT NULL COMMENT '原对象id',
  `create_date` bigint NOT NULL COMMENT '备份时间',
  `tag` varchar(32) DEFAULT NULL COMMENT '标签',
  `type` varchar(32) DEFAULT NULL COMMENT '类型',
  `name` varchar(64) DEFAULT NULL COMMENT '原名称',
  `content` blob COMMENT '备份内容',
  `create_by` varchar(64) DEFAULT NULL COMMENT '操作人',
  PRIMARY KEY (`id`,`create_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `magic_api_file` (
  `file_path` varchar(512) NOT NULL,
  `file_content` mediumtext,
  PRIMARY KEY (`file_path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `qrtz_calendars` (
  `sched_name` varchar(120) NOT NULL COMMENT '调度名称',
  `calendar_name` varchar(200) NOT NULL COMMENT '日历名称',
  `calendar` blob NOT NULL COMMENT '存放持久化calendar对象',
  PRIMARY KEY (`sched_name`,`calendar_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='日历信息表';

CREATE TABLE `qrtz_fired_triggers` (
  `sched_name` varchar(120) NOT NULL COMMENT '调度名称',
  `entry_id` varchar(95) NOT NULL COMMENT '调度器实例id',
  `trigger_name` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `instance_name` varchar(200) NOT NULL COMMENT '调度器实例名',
  `fired_time` bigint NOT NULL COMMENT '触发的时间',
  `sched_time` bigint NOT NULL COMMENT '定时器制定的时间',
  `priority` int NOT NULL COMMENT '优先级',
  `state` varchar(16) NOT NULL COMMENT '状态',
  `job_name` varchar(200) DEFAULT NULL COMMENT '任务名称',
  `job_group` varchar(200) DEFAULT NULL COMMENT '任务组名',
  `is_nonconcurrent` varchar(1) DEFAULT NULL COMMENT '是否并发',
  `requests_recovery` varchar(1) DEFAULT NULL COMMENT '是否接受恢复执行',
  PRIMARY KEY (`sched_name`,`entry_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='已触发的触发器表';

CREATE TABLE `qrtz_job_details` (
  `sched_name` varchar(120) NOT NULL COMMENT '调度名称',
  `job_name` varchar(200) NOT NULL COMMENT '任务名称',
  `job_group` varchar(200) NOT NULL COMMENT '任务组名',
  `description` varchar(250) DEFAULT NULL COMMENT '相关介绍',
  `job_class_name` varchar(250) NOT NULL COMMENT '执行任务类名称',
  `is_durable` varchar(1) NOT NULL COMMENT '是否持久化',
  `is_nonconcurrent` varchar(1) NOT NULL COMMENT '是否并发',
  `is_update_data` varchar(1) NOT NULL COMMENT '是否更新数据',
  `requests_recovery` varchar(1) NOT NULL COMMENT '是否接受恢复执行',
  `job_data` blob COMMENT '存放持久化job对象',
  PRIMARY KEY (`sched_name`,`job_name`,`job_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务详细信息表';

CREATE TABLE `qrtz_locks` (
  `sched_name` varchar(120) NOT NULL COMMENT '调度名称',
  `lock_name` varchar(40) NOT NULL COMMENT '悲观锁名称',
  PRIMARY KEY (`sched_name`,`lock_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='存储的悲观锁信息表';

CREATE TABLE `qrtz_paused_trigger_grps` (
  `sched_name` varchar(120) NOT NULL COMMENT '调度名称',
  `trigger_group` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  PRIMARY KEY (`sched_name`,`trigger_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='暂停的触发器表';

CREATE TABLE `qrtz_scheduler_state` (
  `sched_name` varchar(120) NOT NULL COMMENT '调度名称',
  `instance_name` varchar(200) NOT NULL COMMENT '实例名称',
  `last_checkin_time` bigint NOT NULL COMMENT '上次检查时间',
  `checkin_interval` bigint NOT NULL COMMENT '检查间隔时间',
  PRIMARY KEY (`sched_name`,`instance_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调度器状态表';

CREATE TABLE `qrtz_triggers` (
  `sched_name` varchar(120) NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) NOT NULL COMMENT '触发器的名字',
  `trigger_group` varchar(200) NOT NULL COMMENT '触发器所属组的名字',
  `job_name` varchar(200) NOT NULL COMMENT 'qrtz_job_details表job_name的外键',
  `job_group` varchar(200) NOT NULL COMMENT 'qrtz_job_details表job_group的外键',
  `description` varchar(250) DEFAULT NULL COMMENT '相关介绍',
  `next_fire_time` bigint DEFAULT NULL COMMENT '上一次触发时间（毫秒）',
  `prev_fire_time` bigint DEFAULT NULL COMMENT '下一次触发时间（默认为-1表示不触发）',
  `priority` int DEFAULT NULL COMMENT '优先级',
  `trigger_state` varchar(16) NOT NULL COMMENT '触发器状态',
  `trigger_type` varchar(8) NOT NULL COMMENT '触发器的类型',
  `start_time` bigint NOT NULL COMMENT '开始时间',
  `end_time` bigint DEFAULT NULL COMMENT '结束时间',
  `calendar_name` varchar(200) DEFAULT NULL COMMENT '日程表名称',
  `misfire_instr` smallint DEFAULT NULL COMMENT '补偿执行的策略',
  `job_data` blob COMMENT '存放持久化job对象',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`),
  KEY `sched_name` (`sched_name`,`job_name`,`job_group`),
  CONSTRAINT `qrtz_triggers_ibfk_1` FOREIGN KEY (`sched_name`, `job_name`, `job_group`) REFERENCES `qrtz_job_details` (`sched_name`, `job_name`, `job_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='触发器详细信息表';

CREATE TABLE `qrtz_blob_triggers` (
  `sched_name` varchar(120) NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `blob_data` blob COMMENT '存放持久化Trigger对象',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`),
  CONSTRAINT `qrtz_blob_triggers_ibfk_1` FOREIGN KEY (`sched_name`, `trigger_name`, `trigger_group`) REFERENCES `qrtz_triggers` (`sched_name`, `trigger_name`, `trigger_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Blob类型的触发器表';

CREATE TABLE `qrtz_cron_triggers` (
  `sched_name` varchar(120) NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `cron_expression` varchar(200) NOT NULL COMMENT 'cron表达式',
  `time_zone_id` varchar(80) DEFAULT NULL COMMENT '时区',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`),
  CONSTRAINT `qrtz_cron_triggers_ibfk_1` FOREIGN KEY (`sched_name`, `trigger_name`, `trigger_group`) REFERENCES `qrtz_triggers` (`sched_name`, `trigger_name`, `trigger_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Cron类型的触发器表';

CREATE TABLE `qrtz_simple_triggers` (
  `sched_name` varchar(120) NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `repeat_count` bigint NOT NULL COMMENT '重复的次数统计',
  `repeat_interval` bigint NOT NULL COMMENT '重复的间隔时间',
  `times_triggered` bigint NOT NULL COMMENT '已经触发的次数',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`),
  CONSTRAINT `qrtz_simple_triggers_ibfk_1` FOREIGN KEY (`sched_name`, `trigger_name`, `trigger_group`) REFERENCES `qrtz_triggers` (`sched_name`, `trigger_name`, `trigger_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='简单触发器的信息表';

CREATE TABLE `qrtz_simprop_triggers` (
  `sched_name` varchar(120) NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `str_prop_1` varchar(512) DEFAULT NULL COMMENT 'String类型的trigger的第一个参数',
  `str_prop_2` varchar(512) DEFAULT NULL COMMENT 'String类型的trigger的第二个参数',
  `str_prop_3` varchar(512) DEFAULT NULL COMMENT 'String类型的trigger的第三个参数',
  `int_prop_1` int DEFAULT NULL COMMENT 'int类型的trigger的第一个参数',
  `int_prop_2` int DEFAULT NULL COMMENT 'int类型的trigger的第二个参数',
  `long_prop_1` bigint DEFAULT NULL COMMENT 'long类型的trigger的第一个参数',
  `long_prop_2` bigint DEFAULT NULL COMMENT 'long类型的trigger的第二个参数',
  `dec_prop_1` decimal(13,4) DEFAULT NULL COMMENT 'decimal类型的trigger的第一个参数',
  `dec_prop_2` decimal(13,4) DEFAULT NULL COMMENT 'decimal类型的trigger的第二个参数',
  `bool_prop_1` varchar(1) DEFAULT NULL COMMENT 'Boolean类型的trigger的第一个参数',
  `bool_prop_2` varchar(1) DEFAULT NULL COMMENT 'Boolean类型的trigger的第二个参数',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`),
  CONSTRAINT `qrtz_simprop_triggers_ibfk_1` FOREIGN KEY (`sched_name`, `trigger_name`, `trigger_group`) REFERENCES `qrtz_triggers` (`sched_name`, `trigger_name`, `trigger_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='同步机制的行锁表';

CREATE TABLE `sys_agent_commission_account` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '账户ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `total_earned` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '累计收益',
  `self_earned` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '自购累计收益',
  `promotion_earned` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '推广累计收益',
  `withdrawn` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '已提现金额',
  `balance` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '账户余额',
  `frozen_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '冻结金额',
  `status` char(1) NOT NULL DEFAULT '0' COMMENT '状态(0:正常 1:冻结)',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='代理佣金账户表';

CREATE TABLE `sys_agent_commission_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '配置ID',
  `agent_level` int NOT NULL COMMENT '代理层级(1:一级代理 2:二级代理)',
  `self_commission_rate` decimal(5,2) NOT NULL DEFAULT '0.00' COMMENT '自购佣金比例(%)',
  `promotion_commission_rate` decimal(5,2) NOT NULL DEFAULT '0.00' COMMENT '推广佣金比例(%)',
  `status` char(1) NOT NULL DEFAULT '0' COMMENT '状态(0:正常 1:停用)',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_level` (`agent_level`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='代理佣金配置表';

CREATE TABLE `sys_agent_union_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `apply_id` bigint NOT NULL COMMENT '代理申请ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `union_platform_id` bigint NOT NULL COMMENT '联盟平台ID',
  `platform_type` varchar(20) NOT NULL COMMENT '联盟类型(冗余字段,方便查询)',
  `status` char(1) DEFAULT '1' COMMENT '关联状态(0-已取消,1-正常)',
  `auth_status` char(1) DEFAULT '0' COMMENT '授权状态（0-未授权，1-已授权）',
  `custom_parameters` varchar(500) DEFAULT NULL COMMENT '自定义参数（JSON格式，存储授权备案时的custom_parameters等信息）',
  `AUTH_URL` text COMMENT '授权备案URL',
  `remark` varchar(500) DEFAULT NULL COMMENT '澶囨敞',
  `create_by` varchar(64) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_apply_id` (`apply_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_union_platform_id` (`union_platform_id`),
  KEY `idx_platform_type` (`platform_type`)
) ENGINE=InnoDB AUTO_INCREMENT=90 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='代理联盟关联表';

CREATE TABLE `sys_commission_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '配置ID',
  `config_key` varchar(50) NOT NULL COMMENT '配置键(self_rate:自购比例 promotion_rate:推广比例)',
  `config_value` decimal(5,2) NOT NULL COMMENT '配置值(%)',
  `status` char(1) NOT NULL DEFAULT '0' COMMENT '状态(0:正常 1:停用)',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='佣金配置表-简化版';

CREATE TABLE `sys_commission_flow` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '流水ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `flow_type` varchar(20) NOT NULL COMMENT '流水类型(income_self:自购收入 income_promotion:推广收入 withdraw:提现 refund:退款)',
  `amount` decimal(10,2) NOT NULL COMMENT '变动金额',
  `balance_before` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '变动前余额',
  `balance_after` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '变动后余额',
  `order_commission_id` bigint DEFAULT NULL COMMENT '关联订单佣金记录ID',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_flow_type` (`flow_type`),
  KEY `idx_order_commission_id` (`order_commission_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='佣金流水表';

CREATE TABLE `sys_config` (
  `config_id` int NOT NULL AUTO_INCREMENT COMMENT '参数主键',
  `config_name` varchar(100) DEFAULT '' COMMENT '参数名称',
  `config_key` varchar(100) DEFAULT '' COMMENT '参数键名',
  `config_value` varchar(500) DEFAULT '' COMMENT '参数键值',
  `config_type` char(1) DEFAULT 'N' COMMENT '系统内置（Y是 N否）',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`config_id`)
) ENGINE=InnoDB AUTO_INCREMENT=102 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='参数配置表';

CREATE TABLE `sys_dept` (
  `dept_id` bigint NOT NULL AUTO_INCREMENT COMMENT '部门id',
  `parent_id` bigint DEFAULT '0' COMMENT '父部门id',
  `ancestors` varchar(50) DEFAULT '' COMMENT '祖级列表',
  `dept_name` varchar(30) DEFAULT '' COMMENT '部门名称',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `leader` varchar(20) DEFAULT NULL COMMENT '负责人',
  `phone` varchar(11) DEFAULT NULL COMMENT '联系电话',
  `email` varchar(50) DEFAULT NULL COMMENT '邮箱',
  `status` char(1) DEFAULT '0' COMMENT '部门状态（0正常 1停用）',
  `del_flag` char(1) DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`dept_id`)
) ENGINE=InnoDB AUTO_INCREMENT=200 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='部门表';

CREATE TABLE `sys_dict_data` (
  `dict_code` bigint NOT NULL AUTO_INCREMENT COMMENT '字典编码',
  `dict_sort` int DEFAULT '0' COMMENT '字典排序',
  `dict_label` varchar(100) DEFAULT '' COMMENT '字典标签',
  `dict_value` varchar(100) DEFAULT '' COMMENT '字典键值',
  `dict_type` varchar(100) DEFAULT '' COMMENT '字典类型',
  `css_class` varchar(100) DEFAULT NULL COMMENT '样式属性（其他样式扩展）',
  `list_class` varchar(100) DEFAULT NULL COMMENT '表格回显样式',
  `is_default` char(1) DEFAULT 'N' COMMENT '是否默认（Y是 N否）',
  `status` char(1) DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`dict_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1049 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典数据表';

CREATE TABLE `sys_dict_type` (
  `dict_id` bigint NOT NULL AUTO_INCREMENT COMMENT '字典主键',
  `dict_name` varchar(100) DEFAULT '' COMMENT '字典名称',
  `dict_type` varchar(100) DEFAULT '' COMMENT '字典类型',
  `status` char(1) DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`dict_id`),
  UNIQUE KEY `dict_type` (`dict_type`)
) ENGINE=InnoDB AUTO_INCREMENT=109 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典类型表';

CREATE TABLE `sys_job` (
  `job_id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  `job_name` varchar(64) NOT NULL DEFAULT '' COMMENT '任务名称',
  `job_group` varchar(64) NOT NULL DEFAULT 'DEFAULT' COMMENT '任务组名',
  `invoke_target` varchar(500) NOT NULL COMMENT '调用目标字符串',
  `cron_expression` varchar(255) DEFAULT '' COMMENT 'cron执行表达式',
  `misfire_policy` varchar(20) DEFAULT '3' COMMENT '计划执行错误策略（1立即执行 2执行一次 3放弃执行）',
  `concurrent` char(1) DEFAULT '1' COMMENT '是否并发执行（0允许 1禁止）',
  `status` char(1) DEFAULT '0' COMMENT '状态（0正常 1暂停）',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT '' COMMENT '备注信息',
  PRIMARY KEY (`job_id`,`job_name`,`job_group`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='定时任务调度表';

CREATE TABLE `sys_job_log` (
  `job_log_id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务日志ID',
  `job_name` varchar(64) NOT NULL COMMENT '任务名称',
  `job_group` varchar(64) NOT NULL COMMENT '任务组名',
  `invoke_target` varchar(500) NOT NULL COMMENT '调用目标字符串',
  `job_message` varchar(500) DEFAULT NULL COMMENT '日志信息',
  `status` char(1) DEFAULT '0' COMMENT '执行状态（0正常 1失败）',
  `exception_info` varchar(2000) DEFAULT '' COMMENT '异常信息',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`job_log_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='定时任务调度日志表';

CREATE TABLE `sys_logininfor` (
  `info_id` bigint NOT NULL AUTO_INCREMENT COMMENT '访问ID',
  `user_name` varchar(50) DEFAULT '' COMMENT '用户账号',
  `ipaddr` varchar(128) DEFAULT '' COMMENT '登录IP地址',
  `login_location` varchar(255) DEFAULT '' COMMENT '登录地点',
  `browser` varchar(50) DEFAULT '' COMMENT '浏览器类型',
  `os` varchar(50) DEFAULT '' COMMENT '操作系统',
  `status` char(1) DEFAULT '0' COMMENT '登录状态（0成功 1失败）',
  `msg` varchar(255) DEFAULT '' COMMENT '提示消息',
  `login_time` datetime DEFAULT NULL COMMENT '访问时间',
  PRIMARY KEY (`info_id`),
  KEY `idx_sys_logininfor_s` (`status`),
  KEY `idx_sys_logininfor_lt` (`login_time`)
) ENGINE=InnoDB AUTO_INCREMENT=133 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统访问记录';

CREATE TABLE `sys_menu` (
  `menu_id` bigint NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `menu_name` varchar(50) NOT NULL COMMENT '菜单名称',
  `parent_id` bigint DEFAULT '0' COMMENT '父菜单ID',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `path` varchar(200) DEFAULT '' COMMENT '路由地址',
  `component` varchar(255) DEFAULT NULL COMMENT '组件路径',
  `query` varchar(255) DEFAULT NULL COMMENT '路由参数',
  `route_name` varchar(50) DEFAULT '' COMMENT '路由名称',
  `is_frame` int DEFAULT '1' COMMENT '是否为外链（0是 1否）',
  `is_cache` int DEFAULT '0' COMMENT '是否缓存（0缓存 1不缓存）',
  `menu_type` char(1) DEFAULT '' COMMENT '菜单类型（M目录 C菜单 F按钮）',
  `visible` char(1) DEFAULT '0' COMMENT '菜单状态（0显示 1隐藏）',
  `status` char(1) DEFAULT '0' COMMENT '菜单状态（0正常 1停用）',
  `perms` varchar(100) DEFAULT NULL COMMENT '权限标识',
  `icon` varchar(100) DEFAULT '#' COMMENT '菜单图标',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT '' COMMENT '备注',
  PRIMARY KEY (`menu_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2059 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单权限表';

CREATE TABLE `sys_miniapp_login_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `openid` varchar(100) NOT NULL COMMENT '微信OpenID',
  `login_time` datetime NOT NULL COMMENT '登录时间',
  `login_ip` varchar(128) DEFAULT NULL COMMENT '登录IP',
  `login_location` varchar(255) DEFAULT NULL COMMENT '登录地点',
  `browser` varchar(50) DEFAULT NULL COMMENT '浏览器类型',
  `os` varchar(50) DEFAULT NULL COMMENT '操作系统',
  `device_type` varchar(20) DEFAULT NULL COMMENT '设备类型(miniapp-小程序)',
  `device_brand` varchar(50) DEFAULT NULL COMMENT '设备品牌',
  `device_model` varchar(100) DEFAULT NULL COMMENT '设备型号',
  `mini_app_version` varchar(50) DEFAULT NULL COMMENT '小程序版本',
  `wx_version` varchar(50) DEFAULT NULL COMMENT '微信版本',
  `status` char(1) DEFAULT '0' COMMENT '登录状态(0-成功,1-失败)',
  `msg` varchar(255) DEFAULT NULL COMMENT '提示消息',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_openid` (`openid`),
  KEY `idx_login_time` (`login_time`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='小程序登录记录表';

CREATE TABLE `sys_miniapp_session` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  `session_key` varchar(100) NOT NULL COMMENT '微信会话密钥',
  `openid` varchar(100) NOT NULL COMMENT '用户OpenID',
  `unionid` varchar(100) DEFAULT NULL COMMENT '用户UnionID',
  `token` varchar(500) NOT NULL COMMENT 'JWT Token',
  `expire_time` datetime NOT NULL COMMENT '过期时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`),
  KEY `idx_token` (`token`(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='小程序会话表';

CREATE TABLE `sys_notice` (
  `notice_id` int NOT NULL AUTO_INCREMENT COMMENT '公告ID',
  `notice_title` varchar(50) NOT NULL COMMENT '公告标题',
  `notice_type` char(1) NOT NULL COMMENT '公告类型（1通知 2公告）',
  `notice_content` longblob COMMENT '公告内容',
  `status` char(1) DEFAULT '0' COMMENT '公告状态（0正常 1关闭）',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`notice_id`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知公告表';

CREATE TABLE `sys_oper_log` (
  `oper_id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志主键',
  `title` varchar(50) DEFAULT '' COMMENT '模块标题',
  `business_type` int DEFAULT '0' COMMENT '业务类型（0其它 1新增 2修改 3删除）',
  `method` varchar(200) DEFAULT '' COMMENT '方法名称',
  `request_method` varchar(10) DEFAULT '' COMMENT '请求方式',
  `operator_type` int DEFAULT '0' COMMENT '操作类别（0其它 1后台用户 2手机端用户）',
  `oper_name` varchar(50) DEFAULT '' COMMENT '操作人员',
  `dept_name` varchar(50) DEFAULT '' COMMENT '部门名称',
  `oper_url` varchar(255) DEFAULT '' COMMENT '请求URL',
  `oper_ip` varchar(128) DEFAULT '' COMMENT '主机地址',
  `oper_location` varchar(255) DEFAULT '' COMMENT '操作地点',
  `oper_param` varchar(2000) DEFAULT '' COMMENT '请求参数',
  `json_result` varchar(2000) DEFAULT '' COMMENT '返回参数',
  `status` int DEFAULT '0' COMMENT '操作状态（0正常 1异常）',
  `error_msg` varchar(2000) DEFAULT '' COMMENT '错误消息',
  `oper_time` datetime DEFAULT NULL COMMENT '操作时间',
  `cost_time` bigint DEFAULT '0' COMMENT '消耗时间',
  PRIMARY KEY (`oper_id`),
  KEY `idx_sys_oper_log_bt` (`business_type`),
  KEY `idx_sys_oper_log_s` (`status`),
  KEY `idx_sys_oper_log_ot` (`oper_time`)
) ENGINE=InnoDB AUTO_INCREMENT=280 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作日志记录';

CREATE TABLE `sys_order_commission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `order_id` varchar(100) NOT NULL COMMENT '订单ID',
  `platform_type` varchar(20) NOT NULL COMMENT '平台类型(tbk:淘宝客 jd:京东 pdd:拼多多)',
  `item_id` varchar(100) DEFAULT NULL COMMENT '商品ID',
  `item_title` varchar(500) DEFAULT NULL COMMENT '商品标题',
  `order_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '订单金额',
  `total_commission` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '订单总佣金',
  `agent_user_id` bigint NOT NULL COMMENT '下单代理用户ID',
  `self_commission_rate` decimal(5,2) NOT NULL DEFAULT '0.00' COMMENT '自购佣金比例(%)',
  `self_commission_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '自购佣金金额',
  `has_referrer` char(1) NOT NULL DEFAULT '0' COMMENT '是否有推荐人(0:无 1:有)',
  `referrer_user_id` bigint DEFAULT NULL COMMENT '推荐人用户ID',
  `promotion_commission_rate` decimal(5,2) DEFAULT '0.00' COMMENT '推广佣金比例(%)',
  `promotion_commission_amount` decimal(10,2) DEFAULT '0.00' COMMENT '推广佣金金额',
  `platform_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '平台收入',
  `order_status` varchar(20) NOT NULL DEFAULT 'pending' COMMENT '订单状态(pending:待结算 settled:已结算 cancelled:已取消)',
  `settle_time` datetime DEFAULT NULL COMMENT '结算时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_platform` (`order_id`,`platform_type`),
  KEY `idx_agent_user_id` (`agent_user_id`),
  KEY `idx_referrer_user_id` (`referrer_user_id`),
  KEY `idx_order_status` (`order_status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单佣金记录表';

CREATE TABLE `sys_post` (
  `post_id` bigint NOT NULL AUTO_INCREMENT COMMENT '岗位ID',
  `post_code` varchar(64) NOT NULL COMMENT '岗位编码',
  `post_name` varchar(50) NOT NULL COMMENT '岗位名称',
  `post_sort` int NOT NULL COMMENT '显示顺序',
  `status` char(1) NOT NULL COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`post_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='岗位信息表';

CREATE TABLE `sys_role` (
  `role_id` bigint NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `role_name` varchar(30) NOT NULL COMMENT '角色名称',
  `role_key` varchar(100) NOT NULL COMMENT '角色权限字符串',
  `role_sort` int NOT NULL COMMENT '显示顺序',
  `data_scope` char(1) DEFAULT '1' COMMENT '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限）',
  `menu_check_strictly` tinyint(1) DEFAULT '1' COMMENT '菜单树选择项是否关联显示',
  `dept_check_strictly` tinyint(1) DEFAULT '1' COMMENT '部门树选择项是否关联显示',
  `status` char(1) NOT NULL COMMENT '角色状态（0正常 1停用）',
  `del_flag` char(1) DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色信息表';

CREATE TABLE `sys_role_dept` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `dept_id` bigint NOT NULL COMMENT '部门ID',
  PRIMARY KEY (`role_id`,`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色和部门关联表';

CREATE TABLE `sys_role_menu` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (`role_id`,`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色和菜单关联表';

CREATE TABLE `sys_tbk_agent_apply` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '申请ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `mini_app_id` varchar(100) NOT NULL COMMENT '微信小程序AppID',
  `real_name` varchar(50) NOT NULL COMMENT '真实姓名',
  `phone` varchar(20) NOT NULL COMMENT '手机号',
  `wechat` varchar(50) NOT NULL COMMENT '微信号',
  `invitation_code` varchar(20) DEFAULT NULL COMMENT '代理专属邀请码',
  `referrer_invitation_code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '上级邀请码（注册时填写的邀请码，判断是否有上级的关键字段）',
  `commission_account_id` bigint DEFAULT NULL COMMENT '浣ｉ噾璐︽埛ID',
  `self_commission_rate` decimal(5,2) DEFAULT '0.00' COMMENT '鑷?喘浣ｉ噾姣斾緥(%)',
  `promotion_commission_rate` decimal(5,2) DEFAULT '0.00' COMMENT '鎺ㄥ箍浣ｉ噾姣斾緥(%)',
  `agent_level` int DEFAULT '1' COMMENT '代理级别(1-10级)',
  `can_invite_subordinate` char(1) DEFAULT '1' COMMENT '鏄?惁鍙?個璇蜂笅绾?0-涓嶅彲閭??,1-鍙?個璇?',
  `apply_platform_types` varchar(100) DEFAULT NULL COMMENT '申请的联盟类型(多个逗号分隔,如:tbk,jd,pdd)',
  `status` char(1) DEFAULT '0' COMMENT '申请状态(0-待审核,1-已生效,2-已失效)',
  `audit_by` varchar(64) DEFAULT NULL COMMENT '审核人',
  `audit_time` datetime DEFAULT NULL COMMENT '审核时间',
  `audit_remark` varchar(500) DEFAULT NULL COMMENT '审核备注',
  `create_by` varchar(64) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mini_app_id` (`mini_app_id`),
  UNIQUE KEY `uk_invitation_code` (`invitation_code`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_referrer_invitation_code` (`referrer_invitation_code`),
  KEY `idx_agent_level` (`agent_level`),
  KEY `idx_commission_account_id` (`commission_account_id`),
  KEY `idx_can_invite` (`can_invite_subordinate`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='淘宝客代理申请表';

CREATE TABLE `sys_union_platform` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `platform_type` varchar(20) NOT NULL COMMENT '联盟类型(tbk-淘宝客,jd-京东,pdd-拼多多,dtk-大淘客)',
  `platform_name` varchar(100) NOT NULL COMMENT '联盟名称',
  `app_key` varchar(200) NOT NULL COMMENT '应用Key/AppID',
  `app_secret` varchar(500) NOT NULL COMMENT '应用Secret',
  `site_id` varchar(100) DEFAULT NULL COMMENT '站点ID(淘宝客专用)',
  `adzone_id` varchar(100) DEFAULT NULL COMMENT '推广位ID',
  `access_token` varchar(500) DEFAULT NULL COMMENT '璁块棶浠ょ墝(鏌愪簺鑱旂洘闇??)',
  `is_quote` char(1) DEFAULT '0' COMMENT '使用状态(0-未被应用,1-被使用,2-失效)',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `status` char(1) DEFAULT '0' COMMENT '状态(0-正常,1-停用)',
  `create_by` varchar(64) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_platform_type` (`platform_type`),
  KEY `idx_is_quote` (`is_quote`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='联盟信息表';

CREATE TABLE `sys_user` (
  `user_id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `dept_id` bigint DEFAULT NULL COMMENT '部门ID',
  `user_name` varchar(30) NOT NULL COMMENT '用户账号',
  `nick_name` varchar(30) NOT NULL COMMENT '用户昵称',
  `user_type` varchar(2) DEFAULT '00' COMMENT '用户类型（00系统用户）',
  `email` varchar(50) DEFAULT '' COMMENT '用户邮箱',
  `phonenumber` varchar(11) DEFAULT '' COMMENT '手机号码',
  `sex` char(1) DEFAULT '0' COMMENT '用户性别（0男 1女 2未知）',
  `avatar` varchar(100) DEFAULT '' COMMENT '头像地址',
  `password` varchar(100) DEFAULT '' COMMENT '密码',
  `status` char(1) DEFAULT '0' COMMENT '账号状态（0正常 1停用）',
  `del_flag` char(1) DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `login_ip` varchar(128) DEFAULT '' COMMENT '最后登录IP',
  `login_date` datetime DEFAULT NULL COMMENT '最后登录时间',
  `pwd_update_date` datetime DEFAULT NULL COMMENT '密码最后更新时间',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=101 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户信息表';

CREATE TABLE `sys_user_post` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `post_id` bigint NOT NULL COMMENT '岗位ID',
  PRIMARY KEY (`user_id`,`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户与岗位关联表';

CREATE TABLE `sys_user_role` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  PRIMARY KEY (`user_id`,`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户和角色关联表';

CREATE TABLE `sys_wechat_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '关联ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `openid` varchar(100) NOT NULL COMMENT '微信openid',
  `unionid` varchar(100) DEFAULT NULL COMMENT '微信unionid',
  `nickname` varchar(100) DEFAULT NULL COMMENT '微信昵称',
  `avatar` varchar(500) DEFAULT NULL COMMENT '微信头像',
  `gender` varchar(1) DEFAULT NULL COMMENT '性别',
  `province` varchar(50) DEFAULT NULL COMMENT '省份',
  `city` varchar(50) DEFAULT NULL COMMENT '城市',
  `country` varchar(50) DEFAULT NULL COMMENT '国家',
  `create_by` varchar(64) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_unionid` (`unionid`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='微信用户关联表';

-- Generic dictionaries and menu definitions only; never import the dump's users,
-- commission settings, SDK configuration, application records or access logs.
INSERT INTO `sys_dict_type` VALUES (1,'用户性别','sys_user_sex','0','admin','2026-03-11 22:47:15','',NULL,'用户性别列表'),(2,'菜单状态','sys_show_hide','0','admin','2026-03-11 22:47:15','',NULL,'菜单状态列表'),(3,'系统开关','sys_normal_disable','0','admin','2026-03-11 22:47:15','',NULL,'系统开关列表'),(4,'任务状态','sys_job_status','0','admin','2026-03-11 22:47:15','',NULL,'任务状态列表'),(5,'任务分组','sys_job_group','0','admin','2026-03-11 22:47:15','',NULL,'任务分组列表'),(6,'系统是否','sys_yes_no','0','admin','2026-03-11 22:47:15','',NULL,'系统是否列表'),(7,'通知类型','sys_notice_type','0','admin','2026-03-11 22:47:15','',NULL,'通知类型列表'),(8,'通知状态','sys_notice_status','0','admin','2026-03-11 22:47:15','',NULL,'通知状态列表'),(9,'操作类型','sys_oper_type','0','admin','2026-03-11 22:47:15','',NULL,'操作类型列表'),(10,'系统状态','sys_common_status','0','admin','2026-03-11 22:47:15','',NULL,'登录状态列表'),(100,'申请状态','sys_tbk_apply_status','0','admin','2026-03-14 16:54:11','',NULL,'代理申请状态'),(101,'联盟类型','union_platform_type','0','admin','2026-04-03 12:22:20','admin','2026-04-04 17:08:21','联盟平台类型列表'),(102,'联盟使用状态','union_quote_status','0','admin','2026-04-03 12:22:20','',NULL,'联盟账号使用状态'),(103,'代理级别下级数量限制','agent_level_subordinate_limit','0','admin','2026-04-08 09:21:14','',NULL,'定义各级别代理可拥有的最大下级数量'),(104,'佣金流水类型','commission_flow_type','0','admin','2026-04-08 15:32:28','admin','2026-04-08 16:43:34','代理佣金流水类型'),(105,'佣金订单状态','commission_order_status','0','admin','2026-04-08 15:32:28','admin','2026-04-08 16:43:34','佣金订单状态'),(106,'佣金结算状态','commission_settlement_status','0','admin','2026-04-08 15:32:28','',NULL,'佣金结算状态');

INSERT INTO `sys_dict_data` VALUES (1,1,'男','0','sys_user_sex','','','Y','0','admin','2026-03-11 22:47:15','',NULL,'性别男'),(2,2,'女','1','sys_user_sex','','','N','0','admin','2026-03-11 22:47:15','',NULL,'性别女'),(3,3,'未知','2','sys_user_sex','','','N','0','admin','2026-03-11 22:47:15','',NULL,'性别未知'),(4,1,'显示','0','sys_show_hide','','primary','Y','0','admin','2026-03-11 22:47:15','',NULL,'显示菜单'),(5,2,'隐藏','1','sys_show_hide','','danger','N','0','admin','2026-03-11 22:47:15','',NULL,'隐藏菜单'),(6,1,'正常','0','sys_normal_disable','','primary','Y','0','admin','2026-03-11 22:47:15','',NULL,'正常状态'),(7,2,'停用','1','sys_normal_disable','','danger','N','0','admin','2026-03-11 22:47:15','',NULL,'停用状态'),(8,1,'正常','0','sys_job_status','','primary','Y','0','admin','2026-03-11 22:47:15','',NULL,'正常状态'),(9,2,'暂停','1','sys_job_status','','danger','N','0','admin','2026-03-11 22:47:15','',NULL,'停用状态'),(10,1,'默认','DEFAULT','sys_job_group','','','Y','0','admin','2026-03-11 22:47:15','',NULL,'默认分组'),(11,2,'系统','SYSTEM','sys_job_group','','','N','0','admin','2026-03-11 22:47:15','',NULL,'系统分组'),(12,1,'是','Y','sys_yes_no','','primary','Y','0','admin','2026-03-11 22:47:15','',NULL,'系统默认是'),(13,2,'否','N','sys_yes_no','','danger','N','0','admin','2026-03-11 22:47:15','',NULL,'系统默认否'),(14,1,'通知','1','sys_notice_type','','warning','Y','0','admin','2026-03-11 22:47:15','',NULL,'通知'),(15,2,'公告','2','sys_notice_type','','success','N','0','admin','2026-03-11 22:47:15','',NULL,'公告'),(16,1,'正常','0','sys_notice_status','','primary','Y','0','admin','2026-03-11 22:47:15','',NULL,'正常状态'),(17,2,'关闭','1','sys_notice_status','','danger','N','0','admin','2026-03-11 22:47:15','',NULL,'关闭状态'),(18,99,'其他','0','sys_oper_type','','info','N','0','admin','2026-03-11 22:47:15','',NULL,'其他操作'),(19,1,'新增','1','sys_oper_type','','info','N','0','admin','2026-03-11 22:47:15','',NULL,'新增操作'),(20,2,'修改','2','sys_oper_type','','info','N','0','admin','2026-03-11 22:47:15','',NULL,'修改操作'),(21,3,'删除','3','sys_oper_type','','danger','N','0','admin','2026-03-11 22:47:15','',NULL,'删除操作'),(22,4,'授权','4','sys_oper_type','','primary','N','0','admin','2026-03-11 22:47:15','',NULL,'授权操作'),(23,5,'导出','5','sys_oper_type','','warning','N','0','admin','2026-03-11 22:47:15','',NULL,'导出操作'),(24,6,'导入','6','sys_oper_type','','warning','N','0','admin','2026-03-11 22:47:15','',NULL,'导入操作'),(25,7,'强退','7','sys_oper_type','','danger','N','0','admin','2026-03-11 22:47:15','',NULL,'强退操作'),(26,8,'生成代码','8','sys_oper_type','','warning','N','0','admin','2026-03-11 22:47:15','',NULL,'生成操作'),(27,9,'清空数据','9','sys_oper_type','','danger','N','0','admin','2026-03-11 22:47:15','',NULL,'清空操作'),(28,1,'成功','0','sys_common_status','','primary','N','0','admin','2026-03-11 22:47:15','',NULL,'正常状态'),(29,2,'失败','1','sys_common_status','','danger','N','0','admin','2026-03-11 22:47:15','',NULL,'停用状态'),(1000,1,'待审核','0','sys_tbk_apply_status','','info','N','0','admin','2026-03-14 16:54:11','',NULL,'待审核状态'),(1001,2,'已生效','1','sys_tbk_apply_status','','success','N','0','admin','2026-03-14 16:54:11','',NULL,'已生效状态'),(1002,3,'已失效','2','sys_tbk_apply_status','','danger','N','0','admin','2026-03-14 16:54:11','',NULL,'已失效状态'),(1003,1,'淘宝客','tbk','union_platform_type','','primary','Y','0','admin','2026-04-03 12:22:20','',NULL,'淘宝联盟'),(1004,2,'京东联盟','jd','union_platform_type','','success','N','0','admin','2026-04-03 12:22:20','',NULL,'京东联盟'),(1005,3,'拼多多','pdd','union_platform_type','','warning','N','0','admin','2026-04-03 12:22:20','',NULL,'拼多多联盟'),(1006,4,'大淘客','dtk','union_platform_type','','info','N','0','admin','2026-04-03 12:22:20','',NULL,'大淘客联盟'),(1007,1,'未被应用','0','union_quote_status','','info','Y','0','admin','2026-04-03 12:22:20','',NULL,'联盟账号未被使用'),(1008,2,'使用中','1','union_quote_status','','success','N','0','admin','2026-04-03 12:22:20','',NULL,'联盟账号正在使用'),(1009,3,'已失效','2','union_quote_status','','danger','N','0','admin','2026-04-03 12:22:20','',NULL,'联盟账号已失效'),(1010,5,'抖音联盟','dy','union_platform_type',NULL,'danger','N','0','admin','2026-04-04 17:10:07','admin','2026-04-04 17:11:59','抖音精选联盟'),(1021,1,'10','1','agent_level_subordinate_limit','','default','N','0','admin','2026-04-08 11:33:32','',NULL,'1级代理最多可拥有10个下级'),(1022,2,'20','2','agent_level_subordinate_limit','','default','N','0','admin','2026-04-08 11:33:32','',NULL,'2级代理最多可拥有20个下级'),(1023,3,'30','3','agent_level_subordinate_limit','','default','N','0','admin','2026-04-08 11:33:32','',NULL,'3级代理最多可拥有30个下级'),(1024,4,'40','4','agent_level_subordinate_limit','','default','N','0','admin','2026-04-08 11:33:32','',NULL,'4级代理最多可拥有40个下级'),(1025,5,'50','5','agent_level_subordinate_limit','','default','N','0','admin','2026-04-08 11:33:32','',NULL,'5级代理最多可拥有50个下级'),(1026,6,'60','6','agent_level_subordinate_limit','','default','N','0','admin','2026-04-08 11:33:32','',NULL,'6级代理最多可拥有60个下级'),(1027,7,'70','7','agent_level_subordinate_limit','','default','N','0','admin','2026-04-08 11:33:32','',NULL,'7级代理最多可拥有70个下级'),(1028,8,'80','8','agent_level_subordinate_limit','','default','N','0','admin','2026-04-08 11:33:32','',NULL,'8级代理最多可拥有80个下级'),(1029,9,'90','9','agent_level_subordinate_limit','','default','N','0','admin','2026-04-08 11:33:32','',NULL,'9级代理最多可拥有90个下级'),(1030,10,'100','10','agent_level_subordinate_limit','','default','N','0','admin','2026-04-08 11:33:32','',NULL,'10级代理最多可拥有100个下级'),(1031,1,'佣金收入','1','commission_flow_type',NULL,NULL,'N','0','admin','2026-04-08 15:32:28','',NULL,NULL),(1032,2,'佣金提现','2','commission_flow_type',NULL,NULL,'N','0','admin','2026-04-08 15:32:28','',NULL,NULL),(1033,3,'佣金退回','3','commission_flow_type',NULL,NULL,'N','0','admin','2026-04-08 15:32:28','',NULL,NULL),(1034,4,'佣金冻结','4','commission_flow_type',NULL,NULL,'N','0','admin','2026-04-08 15:32:28','',NULL,NULL),(1035,5,'佣金解冻','5','commission_flow_type',NULL,NULL,'N','0','admin','2026-04-08 15:32:28','',NULL,NULL),(1036,1,'待确认','0','commission_order_status',NULL,NULL,'N','0','admin','2026-04-08 15:32:28','',NULL,NULL),(1037,2,'已完成','1','commission_order_status',NULL,NULL,'N','0','admin','2026-04-08 15:32:28','',NULL,NULL),(1038,3,'已退款','2','commission_order_status',NULL,NULL,'N','0','admin','2026-04-08 15:32:28','',NULL,NULL),(1039,1,'未结算','0','commission_settlement_status',NULL,NULL,'N','0','admin','2026-04-08 15:32:28','',NULL,NULL),(1040,2,'已结算','1','commission_settlement_status',NULL,NULL,'N','0','admin','2026-04-08 15:32:28','',NULL,NULL),(1041,3,'已冻结','2','commission_settlement_status',NULL,NULL,'N','0','admin','2026-04-08 15:32:28','',NULL,NULL),(1042,1,'自购收入','income_self','commission_flow_type',NULL,NULL,'N','0','admin','2026-04-08 16:43:34','',NULL,'自购订单佣金收入'),(1043,2,'推广收入','income_promotion','commission_flow_type',NULL,NULL,'N','0','admin','2026-04-08 16:43:34','',NULL,'推广下级订单佣金收入'),(1044,3,'提现','withdraw','commission_flow_type',NULL,NULL,'N','0','admin','2026-04-08 16:43:34','',NULL,'佣金提现'),(1045,4,'退款','refund','commission_flow_type',NULL,NULL,'N','0','admin','2026-04-08 16:43:34','',NULL,'订单退款扣除佣金'),(1046,1,'待结算','pending','commission_order_status',NULL,NULL,'N','0','admin','2026-04-08 16:43:34','',NULL,'订单待结算状态'),(1047,2,'已结算','settled','commission_order_status',NULL,NULL,'N','0','admin','2026-04-08 16:43:34','',NULL,'订单已结算状态'),(1048,3,'已取消','cancelled','commission_order_status',NULL,NULL,'N','0','admin','2026-04-08 16:43:34','',NULL,'订单已取消状态');

INSERT INTO `sys_menu` VALUES (1,'系统管理',0,4,'system',NULL,'','',1,0,'M','0','0','','system','admin','2026-03-11 22:47:11','admin','2026-04-09 11:27:26','系统管理目录'),(2,'系统监控',0,5,'monitor',NULL,'','',1,0,'M','0','0','','monitor','admin','2026-03-11 22:47:11','admin','2026-04-09 11:27:40','系统监控目录'),(3,'系统工具',0,6,'tool',NULL,'','',1,0,'M','0','0','','tool','admin','2026-03-11 22:47:11','admin','2026-04-09 11:27:50','系统工具目录'),(4,'若依官网',0,7,'http://ruoyi.vip',NULL,'','',0,0,'M','0','1','','guide','admin','2026-03-11 22:47:11','admin','2026-04-09 11:27:55','若依官网地址'),(100,'用户管理',1,1,'user','system/user/index','','',1,0,'C','0','0','system:user:list','user','admin','2026-03-11 22:47:11','',NULL,'用户管理菜单'),(101,'角色管理',1,2,'role','system/role/index','','',1,0,'C','0','0','system:role:list','peoples','admin','2026-03-11 22:47:11','',NULL,'角色管理菜单'),(102,'菜单管理',1,3,'menu','system/menu/index','','',1,0,'C','0','0','system:menu:list','tree-table','admin','2026-03-11 22:47:11','',NULL,'菜单管理菜单'),(103,'部门管理',1,4,'dept','system/dept/index','','',1,0,'C','0','0','system:dept:list','tree','admin','2026-03-11 22:47:11','',NULL,'部门管理菜单'),(104,'岗位管理',1,5,'post','system/post/index','','',1,0,'C','0','0','system:post:list','post','admin','2026-03-11 22:47:11','',NULL,'岗位管理菜单'),(105,'字典管理',1,6,'dict','system/dict/index','','',1,0,'C','0','0','system:dict:list','dict','admin','2026-03-11 22:47:11','',NULL,'字典管理菜单'),(106,'参数设置',1,7,'config','system/config/index','','',1,0,'C','0','0','system:config:list','edit','admin','2026-03-11 22:47:11','',NULL,'参数设置菜单'),(107,'通知公告',1,8,'notice','system/notice/index','','',1,0,'C','0','0','system:notice:list','message','admin','2026-03-11 22:47:11','',NULL,'通知公告菜单'),(108,'日志管理',1,9,'log','','','',1,0,'M','0','0','','log','admin','2026-03-11 22:47:11','',NULL,'日志管理菜单'),(109,'在线用户',2,1,'online','monitor/online/index','','',1,0,'C','0','0','monitor:online:list','online','admin','2026-03-11 22:47:11','',NULL,'在线用户菜单'),(110,'定时任务',2,2,'job','monitor/job/index','','',1,0,'C','0','0','monitor:job:list','job','admin','2026-03-11 22:47:11','',NULL,'定时任务菜单'),(111,'数据监控',2,3,'druid','monitor/druid/index','','',1,0,'C','0','0','monitor:druid:list','druid','admin','2026-03-11 22:47:11','',NULL,'数据监控菜单'),(112,'服务监控',2,4,'server','monitor/server/index','','',1,0,'C','0','0','monitor:server:list','server','admin','2026-03-11 22:47:11','',NULL,'服务监控菜单'),(113,'缓存监控',2,5,'cache','monitor/cache/index','','',1,0,'C','0','0','monitor:cache:list','redis','admin','2026-03-11 22:47:11','',NULL,'缓存监控菜单'),(114,'缓存列表',2,6,'cacheList','monitor/cache/list','','',1,0,'C','0','0','monitor:cache:list','redis-list','admin','2026-03-11 22:47:11','',NULL,'缓存列表菜单'),(115,'表单构建',3,1,'build','tool/build/index','','',1,0,'C','0','0','tool:build:list','build','admin','2026-03-11 22:47:11','',NULL,'表单构建菜单'),(116,'代码生成',3,2,'gen','tool/gen/index','','',1,0,'C','0','0','tool:gen:list','code','admin','2026-03-11 22:47:11','',NULL,'代码生成菜单'),(117,'系统接口',3,3,'swagger','tool/swagger/index','','',1,0,'C','0','0','tool:swagger:list','swagger','admin','2026-03-11 22:47:11','',NULL,'系统接口菜单'),(500,'操作日志',108,1,'operlog','monitor/operlog/index','','',1,0,'C','0','0','monitor:operlog:list','form','admin','2026-03-11 22:47:11','',NULL,'操作日志菜单'),(501,'登录日志',108,2,'logininfor','monitor/logininfor/index','','',1,0,'C','0','0','monitor:logininfor:list','logininfor','admin','2026-03-11 22:47:11','',NULL,'登录日志菜单'),(1000,'用户查询',100,1,'','','','',1,0,'F','0','0','system:user:query','#','admin','2026-03-11 22:47:11','',NULL,''),(1001,'用户新增',100,2,'','','','',1,0,'F','0','0','system:user:add','#','admin','2026-03-11 22:47:11','',NULL,''),(1002,'用户修改',100,3,'','','','',1,0,'F','0','0','system:user:edit','#','admin','2026-03-11 22:47:11','',NULL,''),(1003,'用户删除',100,4,'','','','',1,0,'F','0','0','system:user:remove','#','admin','2026-03-11 22:47:11','',NULL,''),(1004,'用户导出',100,5,'','','','',1,0,'F','0','0','system:user:export','#','admin','2026-03-11 22:47:11','',NULL,''),(1005,'用户导入',100,6,'','','','',1,0,'F','0','0','system:user:import','#','admin','2026-03-11 22:47:11','',NULL,''),(1006,'重置密码',100,7,'','','','',1,0,'F','0','0','system:user:resetPwd','#','admin','2026-03-11 22:47:11','',NULL,''),(1007,'角色查询',101,1,'','','','',1,0,'F','0','0','system:role:query','#','admin','2026-03-11 22:47:11','',NULL,''),(1008,'角色新增',101,2,'','','','',1,0,'F','0','0','system:role:add','#','admin','2026-03-11 22:47:11','',NULL,''),(1009,'角色修改',101,3,'','','','',1,0,'F','0','0','system:role:edit','#','admin','2026-03-11 22:47:11','',NULL,''),(1010,'角色删除',101,4,'','','','',1,0,'F','0','0','system:role:remove','#','admin','2026-03-11 22:47:11','',NULL,''),(1011,'角色导出',101,5,'','','','',1,0,'F','0','0','system:role:export','#','admin','2026-03-11 22:47:12','',NULL,''),(1012,'菜单查询',102,1,'','','','',1,0,'F','0','0','system:menu:query','#','admin','2026-03-11 22:47:12','',NULL,''),(1013,'菜单新增',102,2,'','','','',1,0,'F','0','0','system:menu:add','#','admin','2026-03-11 22:47:12','',NULL,''),(1014,'菜单修改',102,3,'','','','',1,0,'F','0','0','system:menu:edit','#','admin','2026-03-11 22:47:12','',NULL,''),(1015,'菜单删除',102,4,'','','','',1,0,'F','0','0','system:menu:remove','#','admin','2026-03-11 22:47:12','',NULL,''),(1016,'部门查询',103,1,'','','','',1,0,'F','0','0','system:dept:query','#','admin','2026-03-11 22:47:12','',NULL,''),(1017,'部门新增',103,2,'','','','',1,0,'F','0','0','system:dept:add','#','admin','2026-03-11 22:47:12','',NULL,''),(1018,'部门修改',103,3,'','','','',1,0,'F','0','0','system:dept:edit','#','admin','2026-03-11 22:47:12','',NULL,''),(1019,'部门删除',103,4,'','','','',1,0,'F','0','0','system:dept:remove','#','admin','2026-03-11 22:47:12','',NULL,''),(1020,'岗位查询',104,1,'','','','',1,0,'F','0','0','system:post:query','#','admin','2026-03-11 22:47:12','',NULL,''),(1021,'岗位新增',104,2,'','','','',1,0,'F','0','0','system:post:add','#','admin','2026-03-11 22:47:12','',NULL,''),(1022,'岗位修改',104,3,'','','','',1,0,'F','0','0','system:post:edit','#','admin','2026-03-11 22:47:12','',NULL,''),(1023,'岗位删除',104,4,'','','','',1,0,'F','0','0','system:post:remove','#','admin','2026-03-11 22:47:12','',NULL,''),(1024,'岗位导出',104,5,'','','','',1,0,'F','0','0','system:post:export','#','admin','2026-03-11 22:47:12','',NULL,''),(1025,'字典查询',105,1,'#','','','',1,0,'F','0','0','system:dict:query','#','admin','2026-03-11 22:47:12','',NULL,''),(1026,'字典新增',105,2,'#','','','',1,0,'F','0','0','system:dict:add','#','admin','2026-03-11 22:47:12','',NULL,''),(1027,'字典修改',105,3,'#','','','',1,0,'F','0','0','system:dict:edit','#','admin','2026-03-11 22:47:12','',NULL,''),(1028,'字典删除',105,4,'#','','','',1,0,'F','0','0','system:dict:remove','#','admin','2026-03-11 22:47:12','',NULL,''),(1029,'字典导出',105,5,'#','','','',1,0,'F','0','0','system:dict:export','#','admin','2026-03-11 22:47:12','',NULL,''),(1030,'参数查询',106,1,'#','','','',1,0,'F','0','0','system:config:query','#','admin','2026-03-11 22:47:12','',NULL,''),(1031,'参数新增',106,2,'#','','','',1,0,'F','0','0','system:config:add','#','admin','2026-03-11 22:47:12','',NULL,''),(1032,'参数修改',106,3,'#','','','',1,0,'F','0','0','system:config:edit','#','admin','2026-03-11 22:47:12','',NULL,''),(1033,'参数删除',106,4,'#','','','',1,0,'F','0','0','system:config:remove','#','admin','2026-03-11 22:47:12','',NULL,''),(1034,'参数导出',106,5,'#','','','',1,0,'F','0','0','system:config:export','#','admin','2026-03-11 22:47:12','',NULL,''),(1035,'公告查询',107,1,'#','','','',1,0,'F','0','0','system:notice:query','#','admin','2026-03-11 22:47:12','',NULL,''),(1036,'公告新增',107,2,'#','','','',1,0,'F','0','0','system:notice:add','#','admin','2026-03-11 22:47:12','',NULL,''),(1037,'公告修改',107,3,'#','','','',1,0,'F','0','0','system:notice:edit','#','admin','2026-03-11 22:47:12','',NULL,''),(1038,'公告删除',107,4,'#','','','',1,0,'F','0','0','system:notice:remove','#','admin','2026-03-11 22:47:12','',NULL,''),(1039,'操作查询',500,1,'#','','','',1,0,'F','0','0','monitor:operlog:query','#','admin','2026-03-11 22:47:12','',NULL,''),(1040,'操作删除',500,2,'#','','','',1,0,'F','0','0','monitor:operlog:remove','#','admin','2026-03-11 22:47:12','',NULL,''),(1041,'日志导出',500,3,'#','','','',1,0,'F','0','0','monitor:operlog:export','#','admin','2026-03-11 22:47:12','',NULL,''),(1042,'登录查询',501,1,'#','','','',1,0,'F','0','0','monitor:logininfor:query','#','admin','2026-03-11 22:47:12','',NULL,''),(1043,'登录删除',501,2,'#','','','',1,0,'F','0','0','monitor:logininfor:remove','#','admin','2026-03-11 22:47:12','',NULL,''),(1044,'日志导出',501,3,'#','','','',1,0,'F','0','0','monitor:logininfor:export','#','admin','2026-03-11 22:47:12','',NULL,''),(1045,'账户解锁',501,4,'#','','','',1,0,'F','0','0','monitor:logininfor:unlock','#','admin','2026-03-11 22:47:12','',NULL,''),(1046,'在线查询',109,1,'#','','','',1,0,'F','0','0','monitor:online:query','#','admin','2026-03-11 22:47:12','',NULL,''),(1047,'批量强退',109,2,'#','','','',1,0,'F','0','0','monitor:online:batchLogout','#','admin','2026-03-11 22:47:12','',NULL,''),(1048,'单条强退',109,3,'#','','','',1,0,'F','0','0','monitor:online:forceLogout','#','admin','2026-03-11 22:47:12','',NULL,''),(1049,'任务查询',110,1,'#','','','',1,0,'F','0','0','monitor:job:query','#','admin','2026-03-11 22:47:12','',NULL,''),(1050,'任务新增',110,2,'#','','','',1,0,'F','0','0','monitor:job:add','#','admin','2026-03-11 22:47:12','',NULL,''),(1051,'任务修改',110,3,'#','','','',1,0,'F','0','0','monitor:job:edit','#','admin','2026-03-11 22:47:12','',NULL,''),(1052,'任务删除',110,4,'#','','','',1,0,'F','0','0','monitor:job:remove','#','admin','2026-03-11 22:47:12','',NULL,''),(1053,'状态修改',110,5,'#','','','',1,0,'F','0','0','monitor:job:changeStatus','#','admin','2026-03-11 22:47:12','',NULL,''),(1054,'任务导出',110,6,'#','','','',1,0,'F','0','0','monitor:job:export','#','admin','2026-03-11 22:47:12','',NULL,''),(1055,'生成查询',116,1,'#','','','',1,0,'F','0','0','tool:gen:query','#','admin','2026-03-11 22:47:12','',NULL,''),(1056,'生成修改',116,2,'#','','','',1,0,'F','0','0','tool:gen:edit','#','admin','2026-03-11 22:47:12','',NULL,''),(1057,'生成删除',116,3,'#','','','',1,0,'F','0','0','tool:gen:remove','#','admin','2026-03-11 22:47:12','',NULL,''),(1058,'导入代码',116,4,'#','','','',1,0,'F','0','0','tool:gen:import','#','admin','2026-03-11 22:47:12','',NULL,''),(1059,'预览代码',116,5,'#','','','',1,0,'F','0','0','tool:gen:preview','#','admin','2026-03-11 22:47:12','',NULL,''),(1060,'生成代码',116,6,'#','','','',1,0,'F','0','0','tool:gen:code','#','admin','2026-03-11 22:47:12','',NULL,''),(2000,'联盟管理',0,0,'/union',NULL,NULL,'',1,0,'M','0','0',NULL,'shopping','admin','2026-03-12 16:57:34','',NULL,''),(2001,'淘宝客',2000,2,'union','',NULL,'',1,0,'M','0','0','','monitor','admin','2026-03-12 17:01:01','admin','2026-04-03 23:40:31',''),(2002,'店铺搜索',2001,1,'tbk','union/tbk/shop/index',NULL,'',1,0,'C','0','0','union:tbk:shop','monitor','admin','2026-03-12 17:07:47','',NULL,''),(2003,'淘宝商品列表',2001,2,'tbk/goods','union/tbk/goods/index',NULL,'',1,0,'C','0','0','union:tbk:dg:material:optional:upgrade','list','admin','2026-03-12 23:45:46','admin','2026-03-13 00:43:09',''),(2004,'按钮权限',2001,30,'/tbk/bution',NULL,NULL,'',1,0,'M','1','0','','skill','admin','2026-03-13 00:32:07','admin','2026-03-13 00:37:10',''),(2005,'获取淘口令',2004,1,'',NULL,NULL,'',1,0,'F','0','0','union:tbk:tpwd','#','admin','2026-03-13 00:32:40','',NULL,''),(2006,'物料精选',2001,3,'tbk/promotion','union/tbk/promotion/index',NULL,'',1,0,'C','0','0','union:tbk:dg:optimus:promotion','list','admin','2026-03-13 00:41:08','admin','2026-03-13 00:44:44',''),(2007,'物料精选升级版',2001,4,'tbk/material','union/tbk/material/index',NULL,'',1,0,'C','0','0','union:tbk:dg:material:recommend','list','admin','2026-03-13 00:46:32','',NULL,''),(2008,'物料id列表查询',2004,2,'',NULL,NULL,'',1,0,'F','0','0','union:tbk:optimus:tou:material:ids:get','#','admin','2026-03-13 01:01:17','',NULL,''),(2010,'代理申请管理',2000,0,'tbkApply/index','system/tbkApply/index',NULL,'',1,0,'C','0','0','system:tbkApply:list','system','admin','2026-03-15 11:17:32','admin','2026-04-03 23:39:55',''),(2011,'联盟信息管理',2000,1,'unionPlatform','system/unionPlatform/index',NULL,'',1,0,'C','0','0','system:unionPlatform:list','link','admin','2026-04-03 10:31:40','admin','2026-04-03 23:40:24','联盟信息管理菜单'),(2012,'联盟信息查询',2011,1,'#','',NULL,'',1,0,'F','0','0','system:unionPlatform:query','#','admin','2026-04-03 10:31:40','',NULL,''),(2013,'联盟信息新增',2011,2,'#','',NULL,'',1,0,'F','0','0','system:unionPlatform:add','#','admin','2026-04-03 10:31:40','',NULL,''),(2014,'联盟信息修改',2011,3,'#','',NULL,'',1,0,'F','0','0','system:unionPlatform:edit','#','admin','2026-04-03 10:31:40','',NULL,''),(2015,'联盟信息删除',2011,4,'#','',NULL,'',1,0,'F','0','0','system:unionPlatform:remove','#','admin','2026-04-03 10:31:40','',NULL,''),(2016,'联盟信息导出',2011,5,'#','',NULL,'',1,0,'F','0','0','system:unionPlatform:export','#','admin','2026-04-03 10:31:40','',NULL,''),(2017,'分配联盟',2010,6,'#','',NULL,'',1,0,'F','0','0','system:tbkApply:assign','#','admin','2026-04-03 10:31:40','',NULL,''),(2018,'取消分配',2010,7,'#','',NULL,'',1,0,'F','0','0','system:tbkApply:cancel','#','admin','2026-04-03 10:31:40','',NULL,''),(2038,'佣金管理',0,1,'commission',NULL,NULL,'',1,0,'M','0','0','','money','admin','2026-04-09 09:33:19','admin','2026-04-09 11:27:14','佣金分成系统管理菜单'),(2039,'佣金配置',2038,1,'config','system/commission/config/index',NULL,'',1,0,'C','0','0','system:commission:config:list','edit','admin','2026-04-09 09:33:19','',NULL,'佣金配置管理菜单'),(2040,'佣金配置查询',2039,1,'',NULL,NULL,'',1,0,'F','0','0','system:commission:config:query','#','admin','2026-04-09 09:33:19','',NULL,''),(2041,'佣金配置新增',2039,2,'',NULL,NULL,'',1,0,'F','0','0','system:commission:config:add','#','admin','2026-04-09 09:33:19','',NULL,''),(2042,'佣金配置修改',2039,3,'',NULL,NULL,'',1,0,'F','0','0','system:commission:config:edit','#','admin','2026-04-09 09:33:19','',NULL,''),(2043,'佣金配置删除',2039,4,'',NULL,NULL,'',1,0,'F','0','0','system:commission:config:remove','#','admin','2026-04-09 09:33:19','',NULL,''),(2044,'佣金配置导出',2039,5,'',NULL,NULL,'',1,0,'F','0','0','system:commission:config:export','#','admin','2026-04-09 09:33:19','',NULL,''),(2045,'订单佣金',2038,2,'order','system/commission/order/index',NULL,'',1,0,'C','0','0','system:commission:order:list','shopping','admin','2026-04-09 09:33:19','',NULL,'订单佣金管理菜单'),(2046,'订单佣金查询',2045,1,'',NULL,NULL,'',1,0,'F','0','0','system:commission:order:query','#','admin','2026-04-09 09:33:19','',NULL,''),(2047,'订单结算',2045,2,'',NULL,NULL,'',1,0,'F','0','0','system:commission:order:settle','#','admin','2026-04-09 09:33:19','',NULL,''),(2048,'订单退款',2045,3,'',NULL,NULL,'',1,0,'F','0','0','system:commission:order:refund','#','admin','2026-04-09 09:33:19','',NULL,''),(2049,'订单佣金导出',2045,4,'',NULL,NULL,'',1,0,'F','0','0','system:commission:order:export','#','admin','2026-04-09 09:33:19','',NULL,''),(2050,'佣金账户',2038,3,'account','system/commission/account/index',NULL,'',1,0,'C','0','0','system:commission:account:list','user','admin','2026-04-09 09:33:19','',NULL,'佣金账户管理菜单'),(2051,'佣金账户查询',2050,1,'',NULL,NULL,'',1,0,'F','0','0','system:commission:account:query','#','admin','2026-04-09 09:33:19','',NULL,''),(2052,'佣金账户修改',2050,2,'',NULL,NULL,'',1,0,'F','0','0','system:commission:account:edit','#','admin','2026-04-09 09:33:19','',NULL,''),(2053,'佣金账户导出',2050,3,'',NULL,NULL,'',1,0,'F','0','0','system:commission:account:export','#','admin','2026-04-09 09:33:19','',NULL,''),(2054,'佣金流水',2038,4,'flow','system/commission/flow/index',NULL,'',1,0,'C','0','0','system:commission:flow:list','list','admin','2026-04-09 09:33:19','',NULL,'佣金流水管理菜单'),(2055,'佣金流水查询',2054,1,'',NULL,NULL,'',1,0,'F','0','0','system:commission:flow:query','#','admin','2026-04-09 09:33:19','',NULL,''),(2056,'佣金流水导出',2054,2,'',NULL,NULL,'',1,0,'F','0','0','system:commission:flow:export','#','admin','2026-04-09 09:33:19','',NULL,'');

INSERT INTO sys_dept (dept_id, parent_id, ancestors, dept_name, order_num, leader, status, del_flag, create_by)
VALUES (100, 0, '0', 'LOCAL TEST DEPARTMENT', 0, 'TEST FIXTURE', '0', '0', 'test-fixture');
INSERT INTO sys_post (post_id, post_code, post_name, post_sort, status, create_by)
VALUES (1, 'local-test', 'LOCAL TEST POST', 1, '0', 'test-fixture');
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, status, del_flag, create_by)
VALUES (1, 'LOCAL TEST ADMIN', 'admin', 1, '1', '0', '0', 'test-fixture');
INSERT INTO sys_user (user_id, dept_id, user_name, nick_name, user_type, email, phonenumber, sex, password, status, del_flag, pwd_update_date, create_by, remark)
VALUES (1, 100, 'admin', 'LOCAL TEST ADMIN', '00', '', '', '2', '$2a$10$7OCOjlvHT1sbzSvAhD6iXu2n0vPgFSeKSgGWJpwrvouf8XwOAroZm', '0', '0', NULL, 'test-fixture', 'LOCAL TEST FIXTURE ONLY');
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);
INSERT INTO sys_user_post (user_id, post_id) VALUES (1, 1);

INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_by, remark) VALUES
(1, 'LOCAL TEST skin', 'sys.index.skinName', 'skin-blue', 'Y', 'test-fixture', 'LOCAL TEST FIXTURE'),
(2, 'LOCAL TEST initial password', 'sys.user.initPassword', 'LocalTest123!', 'Y', 'test-fixture', 'LOCAL TEST FIXTURE ONLY'),
(3, 'LOCAL TEST side theme', 'sys.index.sideTheme', 'theme-dark', 'Y', 'test-fixture', 'LOCAL TEST FIXTURE'),
(4, 'LOCAL TEST captcha', 'sys.account.captchaEnabled', 'true', 'Y', 'test-fixture', 'LOCAL TEST FIXTURE'),
(5, 'LOCAL TEST registration', 'sys.account.registerUser', 'false', 'Y', 'test-fixture', 'LOCAL TEST FIXTURE'),
(6, 'LOCAL TEST IP filter', 'sys.login.blackIPList', '', 'Y', 'test-fixture', 'LOCAL TEST FIXTURE'),
(7, 'LOCAL TEST password prompt', 'sys.account.initPasswordModify', '1', 'Y', 'test-fixture', 'LOCAL TEST FIXTURE'),
(8, 'LOCAL TEST password age', 'sys.account.passwordValidateDays', '0', 'Y', 'test-fixture', 'LOCAL TEST FIXTURE');
