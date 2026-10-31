# 并行分工

最多 5 个 Codex 同时开发，按本文件分线。任务的具体要求看 `batch-01.md`，本文件只定谁做什么、什么时候开始、能改哪些文件。

开工前读 `AGENTS.md`。只做自己这条线，别的线的文件不要动。

## 第一批分线

| 轮次 | 线 | 内容 | 开始条件 |
|---|---|---|---|
| 第 1 轮 | 线 1 基础 | T00，合并后接着做 T01 和测试底座 | 现在 |
| 第 2 轮 | 线 2 后台路径 | T03 第 1 项 | 线 1 合并后 |
| 第 2 轮 | 线 3 建库 | T02 | 线 1 合并后 |
| 第 2 轮 | 线 4 账务 | T03 第 2、3 项 + T04 的 `updateUserAccount` | 线 1 合并后 |
| 第 2 轮 | 线 5 认证安全 | T03 第 4 项 + T04 的密码、生产开关 | 线 1 合并后 |
| 第 2 轮 | 线 6 转链身份和邀请 | T03 第 5 项 + T04 的 `userId`、`pid` | 线 1 合并后 |
| 第 3 轮 | 线 7 收尾 | T03 的整体验收 | 第 2 轮全部合并后 |

第 2 轮 5 条线同时开始。线 4、5、6 依赖线 3 的建库，可以先写代码，**但要等线 3 合并后同步 `main`，再跑验收、开 PR**。

合并顺序：线 2 随时；线 3 最先（按下方修改清单改完）；然后线 5 的 `MiniappAuthController.java:167` 小 PR；然后线 4（rebase 改完）；线 5 其余和线 6 不分先后，一次合并一个。

## 各线范围

### 线 1 基础

**能改：** Maven Wrapper、`.github/workflows/ci.yml`、`ruoyi-union-sdk/sdk-stub/`、`scripts/build-sdk-stub.sh`、`.gitignore`，以及 `ruoyi-system`、`ruoyi-admin`、`ruoyi-framework` 的 `pom.xml`（只加测试依赖）。

T00 和 T01 分开提 PR。

**测试底座（和 T01 同一个 PR）：** 当前仓库没有测试依赖，也没有测试文件。给上面三个模块加 test 范围的测试依赖，并各放一个能跑通的最小测试，`./mvnw -B test` 能执行。第 2 轮的线不再改 `pom.xml` 的测试依赖。

### 线 2 后台路径

**能改：** `ruoyi-ui/src/api/system/commissionAccount.js`、`ruoyi-ui/src/api/system/commissionFlow.js`，以及 `ruoyi-ui/src/views/system/commission/` 下调用它们的页面。

**要求：** 只改前端路径，对齐后端现有路径，后端不动。账户接口前缀改成 `/system/commissionAccount`，流水接口对齐 `SysCommissionFlowController`。冻结和解冻用 `/system/commissionAccount/freeze/{accountId}`、`/system/commissionAccount/unfreeze/{accountId}`，HTTP 方法保持前端现有写法，后端接口由线 4 补。

**验收：**
```bash
cd ruoyi-ui && npm install && npm run build:prod
```

### 线 3 建库

**能改：** `README.md`、`docs/deploy-test.md`、`sql/`、`ruoyi-admin/src/main/resources/db/migration/`、`ruoyi-admin/pom.xml`（只加 Flyway 和打包配置）、`ruoyi-admin/src/main/resources/application.yml`（只加 `spring.flyway` 配置）、`docker-compose.yml`、`.github/workflows/ci.yml`、`.env.example`。

**迁移目录固定为：** `ruoyi-admin/src/main/resources/db/migration/`，线 4 也按这个目录放脚本。

**迁移编号：** V1～V99。

**本地启动：** 在 `README.md` 写清本地启动步骤（数据库和 Redis、后端、后台页面），按步骤从空环境能打开后台登录页。步骤要实际跑过。

**打包：** `ruoyi-admin/pom.xml` 的 `spring-boot-maven-plugin` 加 `includeSystemScope`，让 SDK jar 进入最终程序。验收时用 `java -jar` 启动打包结果，不能只用开发模式启动。

**测试环境部署说明：** 新增 `docs/deploy-test.md`，写清在宝塔上手动部署的步骤，由人工执行：
- 这台服务器上还有其他项目。只新建本项目的站点目录，不改动其他站点、全局 Nginx 配置和系统默认 Java。
- Java 21 单独安装，用完整路径启动。
- MySQL 只用本项目的库。Redis 用单独的库编号，并写进配置。
- 后台静态文件由 Nginx 提供，`/prod-api` 反向代理到后端，后端只监听本机。
- 配置通过环境变量传入。文档里的域名、路径、账号一律用占位符，不写真实服务器信息。

**合并前修改（评审结果，分支 `codex/line-3-t02-database`）：**
1. `V2:11` 改 `settle_status VARCHAR(20) NOT NULL DEFAULT 'unsettled'`；`V2:7,9` 改 `DECIMAL(14,4)`；`sql/schema-differences.md:15,20,22,24` 同步。改前确认没有环境执行过 V2。
2. `docs/deploy-test.md` 第 7 节加部署后必改 admin 口令，`V1:711` 的 `initPasswordModify` 设 1。
3. `ci.yml:73` 设 `LINE4_MYSQL_URL`，`:83` 附近要求 skipped=0。

### 线 4 账务

**能改：** `CommissionCalculateServiceImpl`、`ISysAgentCommissionAccountService`、`SysAgentCommissionAccountServiceImpl`、`SysAgentCommissionAccountController`、`SysAgentCommissionAccountMapper`（含 XML）、`SysCommissionFlowMapper`（含 XML）、新增 `SysAgentCommissionConfigMapper.xml`、`SysOrderCommissionMapper.xml`、`SysOrderCommission` 实体。

**要求：** 补冻结和解冻接口，路径见线 2。这两个接口改的是账户状态，不直接改余额。

**合并前修改（评审结果，分支 `codex/line-4-commission-ledger`，当前不通过）：**
1. rebase 到含线 3 的 `main`。
2. 订单 XML `:7-38,77-119` 补 6 个字段及 `remark`；`:121-143` 不把 `settle_status`、`actual_commission` 加进通用 update。
3. 后台新增订单时 buyer 用 `agent_user_id` 兜底；`CommissionCalculateServiceImpl.java:87` 后写 `agent_user_id`。
4. 实体 `:106-107` 注释对齐 `unsettled`、`settled`。
5. `CommissionCalculateServiceImpl.java:295-311,372-376` 修退款统计：用 `refund_self`、`refund_promotion` 两种流水分别减，`:341` 同步。
6. `:198` 落库保留联盟原值。
7. 测试加「下单 → 结算 → 退款」端到端用例及断言，测试不能因缺环境变量被跳过。

**迁移编号：** V100～V199。

**验收：** T04 的前两条：并发入账 100 次、相同业务键重复入账。

### 线 5 认证安全

**能改：** `MiniappAuthController`、`SecurityConfig`、`ruoyi-admin/src/main/resources/application.yml` 的 swagger 开关、`application-druid.yml` 的 Druid 控制台开关。

**要求：**
- 补 `/miniapp/auth/refresh`，请求和返回格式按 `tbk-miniapp/utils/auth.js:233` 现有调用来，小程序不改。
- `MiniappAuthController.java:195` 的固定密码改成每个用户随机生成。
- 只改上面两个 yml 里各自的开关，不动线 3 的 Flyway 配置。
- 先单独提 `MiniappAuthController.java:167` 的小 PR（T04 第 1 条），`MiniAppCommissionController` 要做跨用户隔离测试，这个文件由本线负责。

**迁移编号：** V200～V299。

**验收：** T04 的第 3 条：小程序创建的用户无法登录后台。`/miniapp/auth/refresh` 有测试。用户 A 看不到 B 的流水。

### 线 6 转链身份和邀请

**能改：** `MiniappTbkController`、`MiniappJdController`、`MiniappPddController`、`MiniappDtkController`、`MiniappHdkController`、`SysTbkAgentApply` 相关类和 Mapper、`SysWechatUser` 相关类和 Mapper、`tbk-miniapp/`、`ruoyi-ui/src/views/system/tbkApply/`。

**要求：**
- 邀请参数统一为 `invitationCode`。`MiniappAuthController` 读的名字改动前先核对；要改它时，协调线 5，不在本线直接改。
- 带用户身份的转链取登录态用户；`pid` 不接受客户端传入。
- 大淘客转链失败报错，不回落到不带 pid 的口令。
- `uk_mini_app_id` 保持不动，普通用户账户模型见 `docs/plan.md` 第 9.1 节第 2 项，放第二批。

**迁移编号：** V300～V399。

**验收：** T04 的后两条：伪造 `userId` 不生效、客户端传入 `pid` 不生效。

### 线 7 收尾

**能改：** 只修验收中发现的问题。

**验收：** T03 的 `./mvnw -B test` 和 `ruoyi-ui` 构建，并在 PR 里列出后台菜单逐页检查结果。

## 第二批分线

第一批全部合并后开始。详细任务单到时单独写，这里先定边界。

| 轮次 | 线 | 内容 | 对应方案阶段 |
|---|---|---|---|
| 第 4 轮 | 线 8 契约 | 联盟适配接口、订单行模型、入账接口、新增表的迁移 | 第 5.2、5.3 节 |
| 第 5 轮 | 线 9 查券归因 | `union`、`attribution` | 阶段 2 |
| 第 5 轮 | 线 10 订单 | `order` 和同步任务 | 阶段 3 |
| 第 5 轮 | 线 11 规则账务 | `rule`、`settlement`、`ledger` | 阶段 4 |
| 第 5 轮 | 线 12 提现对账 | `withdraw`、`recon`、`risk` | 阶段 5 |
| 第 5 轮 | 线 13 页面 | 小程序和后台新页面，设计规格完成后开始 | 阶段 6 |

线 8 只定接口、模型和表，不写业务实现。线 9～12 只通过线 8 定的接口互相调用，要改接口时单独提 PR，不在自己的线里改。

## 给 Codex 的指令

每条线开一个 Codex 任务，发一句：

```text
读 AGENTS.md 和 docs/tasks/parallel.md，只做线 N（按 docs/tasks/batch-01.md 的对应要求），完成后开 PR。
```
