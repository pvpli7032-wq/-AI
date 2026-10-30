# 第一批任务：跑通基座

原则：在基座现有代码上修通和增加，不删除、不重写现有模块、接口、页面。

执行顺序和分工见 `parallel.md`，每条线单独开 PR。开工前读 `AGENTS.md`。

## T00 环境探测和 CI

**目标：** 确认当前环境能编译、能跑 MySQL 测试，建立 CI。

**范围：** 新增 Maven Wrapper、`.github/workflows/ci.yml`。不改业务代码。

**要求：**
- CI 使用 Java 21，提供 MySQL 8 和 Redis 服务。
- T01 完成前全量编译会失败，CI 先只编译 `ruoyi-common`。
- 在 PR 说明里报告当前环境：Java 版本、Maven 是否能下载依赖、能否启动 MySQL 和 Redis、是否有 Docker、能否联网。

**验收：**
```bash
./mvnw -B -pl ruoyi-common -am compile
```

## T01 占位 SDK，全量编译通过

**背景：** 三家官方 SDK 要等开放平台审核，不能阻塞后续任务。`ruoyi-union-sdk/pom.xml` 引用 `lib/` 下 5 个 jar，目录不存在，全量编译失败。

**目标：** 用同名占位 jar 让全量编译和应用启动通过，现有代码一行不改。

**范围：** 新增 `ruoyi-union-sdk/sdk-stub/`（占位类源码）、`scripts/build-sdk-stub.sh`（生成 jar），CI 改为先运行脚本再全量编译。

**不能动：** 现有业务代码、`ruoyi-union-sdk/pom.xml`。确实要改时，在 PR 里说明原因。

**要求：**
- 只写现有代码用到的类和方法：淘宝 35 个类、京东 17 个、拼多多 13 个，另有拼多多 2 个未命名的嵌套类型。包名、类名、继承关系和方法签名按调用处推断，核对这 12 个文件：`UnionAutoConfiguration`、`TbkModule`、`TbkApiConstants`、`JdModule`、`JdApiConstants`、`PddModule`、`PddApiConstants`、`TbkSdkService`、`JdSdkService`、`PddApiServiceImpl`、`SysAgentUnionRelationServiceImpl`、`TbkController`。
- 脚本生成这 5 个文件，文件名和 `pom.xml` 完全一致：`taobao-sdk-java-auto_1698396062491-20241216.jar`、`taobao-sdk-java-auto_1698396062491-20241216-source.jar`、`jd-api-sdk-java-20260331.jar`、`pop-sdk-1.19.4-all.jar`、`pop-sdk-1.19.4-all-sources.jar`。
- 脚本只生成缺失的文件，已存在的不覆盖，避免盖掉官方 jar。生成的 jar 不提交。
- 客户端构造函数不抛异常，应用能启动。真正发请求的方法（淘宝和京东的 `execute`、拼多多的 `syncInvoke`）一律抛出写明「占位 SDK」的异常，不返回假数据。
- 不涉及大淘客代码，大淘客接口不受影响。

**验收：**
```bash
bash scripts/build-sdk-stub.sh
./mvnw -B -DskipTests compile
```
PR 里贴原始输出，并列出 `ruoyi-system`、`ruoyi-admin` 是否都编译通过。

## T02 统一建库脚本

**目标：** 空 MySQL 能一键建库，应用能启动。

**范围：** `sql/`、新增 Flyway 依赖和迁移目录、新增 `docker-compose.yml` 启动 MySQL 和 Redis。

**要求：**
- 以 `sql/cps-202604112202-mt.sql` 为准生成 V1 基线，表全部保留。`sys_tbk_agent_apply` 两版定义以它为准，原有 SQL 文件不删。
- 开启 Flyway 的 `out-of-order`，多条线并行时脚本合并顺序不固定。
- 新增一个迁移脚本，补齐代码用到但建表语句缺失的字段，包括 `sys_order_commission` 的 `buyer_user_id`、`estimated_commission`、`commission_rate`、`actual_commission`，`sys_commission_flow` 的 `related_order_id`、`description`。逐一核对实体、Mapper 和建表语句，列出全部差异。

**验收：** CI 用空库执行迁移，以测试用环境变量启动 `ruoyi-admin`，请求 `/captchaImage` 返回 200。

## T03 修通现有功能

**目标：** 基座已有的后台和小程序功能都能用。

**范围：** 只修以下问题，不改功能：
- 后台佣金账户、流水页面的接口路径和后端不一致（`ruoyi-ui/src/api/system/commissionAccount.js`、`commissionFlow.js` 对应 `SysAgentCommissionAccountController`、`SysCommissionFlowController`）
- 后台有冻结和解冻入口，后端缺接口，补上
- `sys_agent_commission_config` 缺 Mapper XML，补上
- 小程序刷新 token 接口 `/miniapp/auth/refresh` 后端缺失，补上
- 小程序邀请参数 `invitationCode` 和 `inviteCode` 不一致，统一

**验收：**
```bash
./mvnw -B test
```
新增的接口有测试；`ruoyi-ui` 能构建。PR 里列出后台菜单逐页检查结果。

## T04 安全和金额修补

**目标：** 修掉会导致冒充和算错钱的问题，为后续功能打底。

**范围：** `MiniappAuthController`、`MiniappTbkController`、`MiniappJdController`、`MiniappPddController`、`MiniappDtkController`、`SecurityConfig`、`CommissionCalculateServiceImpl`、账户 Mapper。

**不能动：** 现有接口路径和页面流程。未登录用户现有的商品浏览保持可用。

**要求：**
- `MiniappAuthController.java:195` 写死的密码改为每个用户随机生成。小程序用户不能用它登录后台。
- 带用户身份的转链取登录态用户。请求里的 `userId` 参数保留兼容但不再作为身份依据，`pid` 不接受客户端传入。
- `updateUserAccount` 改成原子更新：加版本号或行锁，防止并发覆盖；流水加业务唯一键，重复调用不重复入账。
- 生产配置关闭 swagger 和 Druid 控制台（配置关闭，代码保留）。

**验收：** 集成测试覆盖：
- 同一账户并发入账 100 次，余额和流水合计一致（真实 MySQL）
- 相同业务键重复入账，只生效一次
- 小程序创建的用户无法登录后台
- 请求里伪造 `userId`，不影响实际用户
- 客户端传入 `pid`，不生效

## T05 换成官方 SDK

**时机：** 三家开放平台审核通过、SDK 下载后再做，不阻塞第二批。可以一家一家换。

**前置：** 人工从开放平台下载 SDK，放到 `ruoyi-union-sdk/lib`。

**目标：** 现有代码不改逻辑，用官方 SDK 全量编译通过。

**范围：** `ruoyi-union-sdk/pom.xml` 的 jar 文件名、`scripts/build-sdk-stub.sh` 的文件名和该家的占位类、`.gitignore` 放行 `ruoyi-union-sdk/lib/*.jar`。

**不能动：** 现有业务代码。下载的 SDK 版本和基座不同导致编译错误时，只做最小适配，并在 PR 里列出。

**验收：**
```bash
./mvnw -B -DskipTests compile
```

## 后续

第二批开始增加业务功能：粘贴解析、带用户转链、PID 池、查券记录，然后是订单同步、规则、返利、提现、对账，依据 `docs/plan.md`。官方 SDK 到位前，三家官方接口用模拟返回开发和测试，真实联调放到 T05 之后。
