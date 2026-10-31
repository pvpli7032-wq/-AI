# 基线、实体与 Mapper 差异

本清单以 `cps-202604112202-mt.sql` 的 43 张表为基线，对照当前 `ruoyi-common`、`ruoyi-system`、`ruoyi-generator`、`ruoyi-quartz` 的持久化实体、29 份 Mapper XML 和 30 个 Mapper Java 接口。已检查 `SELECT`、关联、筛选、排序、插入和更新，而非只比较 `resultMap`。Java Mapper 没有注解 SQL；数据权限拼接 SQL 使用的 `dept_id`、`role_id`、`ancestors` 均已存在。

`V1` 保留全部表结构；`V2__complete_existing_columns.sql` 只给 5 张表新增 19 列，不删除、重命名、改类型或改约束，不回填业务数据。以下“缺列”与“现有列未被 Mapper 读写”分别记录；增加列不会自动修复 Mapper 或业务逻辑。

V2 已在上轮本地验收库执行（版本 2，checksum `-317547659`），不满足“确认未执行”的修改前提，因此保留 V2 原文，由 `V3__correct_order_commission_types.sql` 修正最终类型。V1 尚未合并，按清单将 `sys.account.initPasswordModify` 种子设为 `1`，并将测试 admin 的 `pwd_update_date` 设为 NULL，使现有登录检查实际显示初始密码修改提示。这会改变 V1 校验和：上轮本地验收库不能直接启动新版制品，必须保留旧库并另建独立空库验收；不得用 repair、删除历史记录或关闭校验掩盖差异。若其他环境也已运行旧 V1，应先协调兼容迁移方案再部署。

金额扩宽只保留已有数值，不能恢复旧精度曾丢失的小数；部署前必须核对既有状态，V3 不会将其他未知显式值静默转为未结算。

## V1 的脱敏调整

V1 保留上述 43 张表的字段、索引、字符集和排序规则。不导入原始脚本中的历史用户、订单、日志、会话、联盟凭据和财务比例种子；保留通用菜单/字典，并使用明确标识为本地测试的管理员、部门、岗位、角色和本地系统配置。测试账号只用于空库开发验收。

原始基线中 `sys_order_commission.self_commission_rate` 以及 `sys_tbk_agent_apply.self_commission_rate/promotion_commission_rate` 带有非零比例默认值，V1 将这些默认值改为中性的 `0.00`，不将上游业务比例作为本项目的真实配置。列名、`DECIMAL(5,2)` 类型及其他结构保持不变；上线业务比例仍应由后续后台配置决定。 建表语句按外键依赖顺序排列（先建 Quartz 父表再建子表），保留原有 5 个外键，不依赖关闭外键校验。

## V2 新增列及 V3 修正后的最终类型

除结算状态外，新增列允许 `NULL`，默认 `NULL`，表示旧数据没有该项信息。按 T02 和线 3 合并前清单，V3 将预估、实际佣金金额修正为 `DECIMAL(14,4)`，比例仍为 `DECIMAL(5,2)`，对应 Java `BigDecimal`；结算状态修正为 `VARCHAR(20) NOT NULL DEFAULT 'unsettled'`，先扩宽状态列，再将 V2 注释定义的 `0/1` 转为 `unsettled/settled`，NULL 置为 `unsettled`；其他显式状态值保留待核查，不推断已结算。ID 用 `BIGINT`，时间用 `DATETIME`。未增加账务唯一键、锁、版本号或订单状态约束，这些属于后续任务。

| 表 | 新列及类型 | 实体 / Mapper 依据 |
|---|---|---|
| `sys_order_commission` | `buyer_user_id BIGINT` | `SysOrderCommission.buyerUserId: Long`；列表筛选引用此列 |
| 同上 | `estimated_commission DECIMAL(14,4)` | `estimatedCommission: BigDecimal`；佣金计算服务设置该持久化业务属性 |
| 同上 | `commission_rate DECIMAL(5,2)` | `commissionRate: BigDecimal`，注释单位为百分比 |
| 同上 | `actual_commission DECIMAL(14,4)` | `actualCommission: BigDecimal`；结算服务设置该属性 |
| 同上 | `order_time DATETIME` | `orderTime: Date`；列表的起止下单时间筛选引用此列 |
| 同上 | `settle_status VARCHAR(20) NOT NULL DEFAULT 'unsettled'` | `settleStatus: String`；列表筛选引用，业务代码写入 `unsettled` / `settled`；实体旧注释由线 4 对齐 |
| `sys_commission_flow` | `related_order_id VARCHAR(100)` | `relatedOrderId: String`；Mapper 查询、筛选、插入、更新均引用。长度与订单表 `order_id` 一致，不冒充数值外键 |
| 同上 | `description VARCHAR(500)` | `description: String`；Mapper 查询、插入、更新均引用 |
| `sys_commission_config` | `description VARCHAR(500)` | `description: String` 为配置描述，属于业务模型属性；当前 Mapper 尚未映射，增加列只补齐存储位置 |
| `sys_miniapp_session` | `session_id BIGINT` | Mapper 主键查询/更新/删除使用；实体 `sessionId: Long` |
| 同上 | `user_id BIGINT` | Mapper 查询、插入、更新使用；实体 `userId: Long` |
| 同上 | `open_id VARCHAR(100)` | Mapper 查询、插入、结果映射使用；与基线 `openid` 命名不同 |
| 同上 | `access_token VARCHAR(500)` | Mapper 查询、插入、更新使用；实体 `accessToken: String`。长度沿用旧 `token` |
| 同上 | `refresh_token VARCHAR(500)` | Mapper 查询、插入、更新使用；实体 `refreshToken: String` |
| 同上 | `expires_at DATETIME` | Mapper 访问令牌有效期筛选、插入、更新使用；实体 `expiresAt: Date` |
| 同上 | `refresh_expires_at DATETIME` | Mapper 刷新令牌有效期筛选、清理、插入、更新使用 |
| 同上 | `update_time DATETIME` | Mapper 更新与结果映射使用；实体从 `BaseEntity` 继承 |
| `sys_miniapp_login_log` | `log_id BIGINT` | Mapper 查询/删除/结果映射使用；实体 `logId: Long` |
| 同上 | `open_id VARCHAR(100)` | Mapper 查询、筛选、插入、结果映射使用；实体 `openId: String` |

新增 `session_id` / `log_id` 不另设自增、主键或唯一键：原表 `id` 自增主键继续存在，不能在未知兼容规则下引入第二套身份分配机制。V2 没有给新旧字段自动复制数据。

## 名称、类型和未闭合的读写差异

| 位置 | 当前差异 | T02 处理及限制 |
|---|---|---|
| `sys_order_commission` | 上述 6 个属性未包含在 Mapper 的结果映射、主查询、插入和更新中；其中 `buyer_user_id`、`order_time`、`settle_status` 已被筛选引用 | V2 补列，消除这些筛选引用的缺列问题；不会声称已持久化或读回这 6 个属性，后续 Mapper 任务仍须补齐 |
| `sys_commission_flow` | 基线 `order_commission_id` 是数值佣金记录 ID，新增 `related_order_id` 是字符串订单号；`remark` 和 `description` 也是独立属性 | 两套列全部保留，不将不同含义强行合并。当前 Mapper 没有读写实体已有的 `orderCommissionId`、`remark` |
| `sys_commission_config` | 实体有 `id`、`status`、`description` 及审计字段；Mapper 只映射 `config_key`、`config_value`、`create_time`、`update_time` | 只补确实缺失的 `description`；现有其他列不重复创建。描述、状态等仍未形成完整读写闭环 |
| `sys_miniapp_session` | 基线 `id/openid/token/expire_time` 对比 Mapper `session_id/open_id/access_token/expires_at`；另有上述 4 个新增字段 | 保留旧列并新增 Mapper 所需列。旧 `openid/token/expire_time/session_key` 的非空约束不变；当前 Mapper 没有写入前三个旧列，单靠本迁移不能修好会话插入或同步新旧 ID/令牌 |
| `MiniappSession` | Mapper 需要 `openId`、`refreshExpiresAt`，实体没有相应属性；按 ID 的 Java 接口和 XML 使用 `String`，实体 `sessionId` 是 `Long`；插入也没有配置返回生成键 | 属于实体/Mapper 不一致，增加数据库列不能解决；后续认证任务须明确会话模型、ID 和有效期契约，不能据此宣称刷新令牌功能可用 |
| `sys_miniapp_login_log` | 基线 `id/openid` 对比 Mapper `log_id/open_id` | 保留旧列并补新列。旧 `openid NOT NULL` 仍需写入；原自增 `id` 与新增 `log_id` 不自动同步，生成键回填为 `logId` 也不会写回 `log_id` |
| `sys_agent_union_relation` | 基线列名 `AUTH_URL`，Mapper 使用 `auth_url` | MySQL 列名不区分大小写，是同一列；不新增重复列、不重命名 |
| `sys_menu.is_frame/is_cache` | 数据库为 `INT`，实体为 `String`，使用字符串数字值 | 现有 JDBC/MyBatis 转换可承接，保留类型；不在 T02 变更开关契约 |
| `sys_dict_data.dict_sort`、`sys_config.config_id`、`sys_notice.notice_id` | 数据库为 `INT`，Java 为 `Long` | 读取可拓宽为 Long，写入仍受原 INT 范围限制；没有丢列，不无故扩大数据库类型 |
| `sys_role.menu_check_strictly/dept_check_strictly` | 数据库 `TINYINT(1)`，Java `boolean` | 正常 JDBC 布尔映射，不是缺列 |
| `sys_notice.notice_content` | 数据库 `LONGBLOB`，Java `String` | Mapper 主查询已经 `CAST(notice_content AS CHAR)`；保留原列及显式转换 |
| `sys_agent_commission_config` | 表和实体字段齐全，但没有对应 Mapper XML | 不补假列；XML 缺失归线 4 |
| 订单、流水 Mapper Java | `selectSysOrderCommissionListByUserId`、`selectSysCommissionFlowListByUserId` 没有对应 XML statement | 属于缺少 Mapper 方法实现，新增列无法解决；记录为后续功能修复 |

现有金额均为 `BigDecimal` / `DECIMAL` 组合。V2 没有重写现有金额精度、比例默认值或账务规则，也不据表结构推断返利比例；V1 的中性默认值脱敏调整见前节。

## 不是缺失数据库列的属性

这些值来自查询投影、关联组装、请求条件或运行时计算，不能把 Java 属性机械转成新列：

| 对象 / 映射 | 无需加列的属性或列别名 | 依据 |
|---|---|---|
| `BaseEntity` | `searchValue`、`params`，以及 `params.begin*` / `params.end*` / 数据权限条件 | 请求搜索和动态 SQL 参数。继承 `createBy/updateBy/remark` 也不代表每张日志或关联表都必须拥有这些列；以持久化 Mapper 实际读写为准 |
| `SysDept`、`SysMenu` / `TreeEntity` | `parentName`、`children` | 父名称查询/展示、树结构组装；`parent_name` 是结果别名，不是树表的新列 |
| `SysRole`、`SysPost` | `flag`；角色的 `menuIds`、`deptIds`、`permissions` | 勾选状态、权限集合、关联表批量入参 |
| `SysUser` | `dept`、`roles`、`roleIds`、`postIds`、筛选用 `roleId` | 关联对象、查询条件；真实关联存放 `sys_user_role`、`sys_user_post` |
| `SysTbkAgentApply.user` | `user_user_id`、`user_user_name`、`user_nick_name`、`user_email`、`user_phonenumber`、`user_sex`、`user_avatar`、`user_status` | 从 `sys_user` JOIN 得到的别名和关联对象 |
| `SysAgentUnionRelation` | `unionPlatform`、`userName`，结果映射 `up_*` | 平台关联对象、展示属性、JOIN 投影别名；`up_*` 的真实列位于 `sys_union_platform` |
| `SysUserMapper` | `dept_status`、`role_status` | 分别为部门、角色 `status` 的 JOIN 别名 |
| `GenTable` | `pkColumn`、`subTable`、`columns`、`treeCode`、`treeParentCode`、`treeName`、`parentMenuId`、`parentMenuName` | 生成器关联对象和由 `options` JSON 解析/组装的扩展选项 |
| `SysJobLog` | `startTime`、`stopTime` | 任务执行时长计算用的运行时值；持久化记录使用 `job_message`、`create_time` |
| `SysOperLog` | `businessTypes` | 多选筛选数组，真实列仍为单值 `business_type` |
| 其他 DTO | `SysUserOnline`、`SysCache`、登录模型、服务器监控模型、路由 VO、`TreeSelect` | Redis、会话、系统监控或响应对象，没有对应的持久化表要求 |
| 生成器元数据查询 | `information_schema.tables/columns` 的列和查询别名 | MySQL 系统元数据，不属于本项目基线缺表/缺列 |

## 现有列未完全映射的清单

这些列在基线已经存在，V1 保留，V2 不重复添加。未被读取或写入不等同于“缺列”。

| 表 | 当前主要 Mapper 未完整读写的现有字段 |
|---|---|
| `sys_agent_commission_account` | `status`（实体存在，Mapper 未映射或更新） |
| `sys_agent_commission_config` | 全表缺少 Mapper XML，列本身齐全 |
| `sys_commission_config` | `id`、`status`、`remark`、`create_by`、`update_by` |
| `sys_commission_flow` | `order_commission_id`、`remark` |
| `sys_order_commission` | `remark`，以及 V2 增加后仍未被读写的 6 个属性 |
| `sys_miniapp_login_log` | `device_brand`、`mini_app_version`、`wx_version`；旧 `id/openid` 与新命名分叉见上文 |
| `sys_miniapp_session` | 旧 `id/openid/token/expire_time/unionid`；身份与令牌兼容问题见上文 |
| `sys_tbk_agent_apply` | `commission_account_id`、`self_commission_rate`、`promotion_commission_rate`、`can_invite_subordinate` 在当前实体/Mapper 中未映射；以此基线为准保留，不能换成 `wechat.sql` 的另一版定义 |
| `sys_union_platform` | `status` 在当前实体/Mapper 中未映射 |
| `sys_user` | `user_type` 在当前实体/Mapper 中未映射 |

## 43 张表逐表核对结果

“无缺列”仅表示静态持久化字段对照没有发现需要新增的列，不承诺页面、Mapper 方法或业务功能全部可用。

| 表 | 对照对象 / 结果 |
|---|---|
| `gen_table` | `GenTable` / `GenTableMapper`，无缺列；运行时扩展属性见上文 |
| `gen_table_column` | `GenTableColumn` / `GenTableColumnMapper`，无缺列 |
| `magic_api_backup` | 当前无直接持久化实体/Mapper；完整保留基线 |
| `magic_api_file` | 当前无直接持久化实体/Mapper；完整保留基线 |
| `qrtz_blob_triggers` | Quartz 内部表，当前无项目自定义实体/Mapper；保留基线 |
| `qrtz_calendars` | Quartz 内部表，保留基线 |
| `qrtz_cron_triggers` | Quartz 内部表，保留基线 |
| `qrtz_fired_triggers` | Quartz 内部表，保留基线 |
| `qrtz_job_details` | Quartz 内部表，保留基线 |
| `qrtz_locks` | Quartz 内部表，保留基线 |
| `qrtz_paused_trigger_grps` | Quartz 内部表，保留基线 |
| `qrtz_scheduler_state` | Quartz 内部表，保留基线 |
| `qrtz_simple_triggers` | Quartz 内部表，保留基线 |
| `qrtz_simprop_triggers` | Quartz 内部表，保留基线 |
| `qrtz_triggers` | Quartz 内部表，保留基线 |
| `sys_agent_commission_account` | `SysAgentCommissionAccount`，无缺列；状态映射缺失 |
| `sys_agent_commission_config` | `SysAgentCommissionConfig`，无缺列；缺 Mapper XML |
| `sys_agent_union_relation` | `SysAgentUnionRelation`，无缺列；`AUTH_URL` 大小写等价 |
| `sys_commission_config` | `SysCommissionConfig`，新增 1 列 |
| `sys_commission_flow` | `SysCommissionFlow`，新增 2 列 |
| `sys_config` | `SysConfig`，无缺列 |
| `sys_dept` | `SysDept`，无缺列 |
| `sys_dict_data` | `SysDictData`，无缺列 |
| `sys_dict_type` | `SysDictType`，无缺列 |
| `sys_job` | `SysJob` / `SysJobMapper`，无缺列 |
| `sys_job_log` | `SysJobLog` / `SysJobLogMapper`，无缺列 |
| `sys_logininfor` | `SysLogininfor`，无缺列 |
| `sys_menu` | `SysMenu`，无缺列 |
| `sys_miniapp_login_log` | `MiniappLoginLog`，新增 2 列；新旧命名仍需兼容 |
| `sys_miniapp_session` | `MiniappSession`，新增 8 列；实体/Mapper 和旧非空约束仍需兼容 |
| `sys_notice` | `SysNotice`，无缺列；内容由显式 CAST 转为字符串 |
| `sys_oper_log` | `SysOperLog`，无缺列 |
| `sys_order_commission` | `SysOrderCommission`，新增 6 列 |
| `sys_post` | `SysPost`，无缺列 |
| `sys_role` | `SysRole`，无缺列 |
| `sys_role_dept` | `SysRoleDept`，无缺列 |
| `sys_role_menu` | `SysRoleMenu`，无缺列 |
| `sys_tbk_agent_apply` | `SysTbkAgentApply`，无缺列；保留未映射的旧业务字段 |
| `sys_union_platform` | `SysUnionPlatform`，无缺列 |
| `sys_user` | `SysUser`，无缺列 |
| `sys_user_post` | `SysUserPost`，无缺列 |
| `sys_user_role` | `SysUserRole`，无缺列 |
| `sys_wechat_user` | `SysWechatUser`，无缺列；邀请码 JOIN 使用已有 `sys_tbk_agent_apply.invitation_code` |

此清单是静态核对记录；真实 MySQL 的空库 Flyway 迁移、打包应用启动和 `/captchaImage` 验收另按 T02 执行。它们不会代替后续账务、会话刷新、联盟接口等功能验收。
