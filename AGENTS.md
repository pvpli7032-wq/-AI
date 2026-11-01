# 开发约定

这是全部规则的唯一入口。接手先读 `docs/handover.md`，业务依据见 `docs/plan.md`，任务见 `docs/tasks/batch-02.md`，现状和决定见 `docs/progress.md`。

项目还在测试阶段，这里只管不能出错的事。其他写法自己判断，能迭代就行。

## 铁规则

出事会亏钱或泄密，没有例外：

1. 只有 `ledger` 能改余额，其他代码调入账接口。
2. 用户身份只从登录态取，接口不信任参数里的 `userId`、`pid`。
3. 金额用 `BigDecimal`，走同一个计算方法，不用 double。
4. 密钥、服务器地址、账号不进仓库。仓库是公开的。
5. 部署上线、改线上库、删用户数据只写脚本或步骤，由用户执行。
6. 不谎报完成。做不完或卡住，写清卡在哪、做了哪些、没做哪些。

## 文件放哪里

不要新建顶层目录，不要把代码放到下表以外的位置。确实放不下时，在 PR 里说明后再加一行。

| 内容 | 位置 |
|---|---|
| 联盟接口 | `ruoyi-union-sdk/src/main/java/com/ruoyi/union/`，业务用的适配放 `adapter/` |
| 业务逻辑 | `ruoyi-system/src/main/java/com/ruoyi/system/cps/<业务包>/` |
| Mapper 接口 | `ruoyi-system/src/main/java/com/ruoyi/system/cps/mapper/` |
| Mapper XML | `ruoyi-system/src/main/resources/mapper/cps/` |
| 小程序接口 | `ruoyi-admin/src/main/java/com/ruoyi/web/controller/miniapp/` |
| 后台接口 | `ruoyi-admin/src/main/java/com/ruoyi/web/controller/cps/` |
| 定时任务 | `ruoyi-quartz/src/main/java/com/ruoyi/quartz/task/` |
| 数据库迁移 | `ruoyi-admin/src/main/resources/db/migration/` |
| 后台页面 | `ruoyi-ui/src/views/`、`ruoyi-ui/src/api/`、`ruoyi-ui/src/router/` |
| 小程序 | `tbk-miniapp/` |
| 测试 | 对应模块的 `src/test/` |
| 文档 | `docs/`，设计规格放 `design/` |
| 脚本 | `scripts/` |

## 模块分工

业务包都在 `ruoyi-system` 的 `cps/` 下，新业务先找现有的包，确实不属于任何一个才加新包，并更新本表。

| 业务包 | 管什么 |
|---|---|
| `contract` | 模块之间共用的接口、模型、状态 |
| `attribution` | PID 池、查券记录、归因、无主订单 |
| `order` | 原始数据、订单行、状态机 |
| `rule` | 规则、版本 |
| `settlement` | 返利明细、奖励计算、冻结解冻、追回 |
| `ledger` | 账户、分录、余额重算 |
| `withdraw` | 提现单、审核、通道接口 |
| `recon` | 对账、差异处理、敞口计算 |
| `risk` | 风险标记、提现分层、告警 |

调用方向：`order → attribution → settlement → ledger`，只能单向，模块之间通过 `contract` 的接口调用，不直接用别的模块的内部类或 Mapper。控制器只做入参和调用，业务写在业务包里。要改接口，在 PR 说明里写清即可。

## 加新东西改哪里

| 要加什么 | 改哪里 |
|---|---|
| 新联盟平台 | `union` 下实现接口和 `adapter`，`order` 加状态映射，公共字典加平台 |
| 新奖励或补贴 | `rule` 加规则，`settlement` 加计算，公共字典加类型 |
| 新余额变动类型 | `ledger` 加类型和入账，公共字典加类型 |
| 新后台页面 | `views`、`api`、`router`，菜单和权限写迁移 |
| 新小程序页面 | `pages`，登记到 `app.json` |
| 新定时任务 | 业务写在业务包，`ruoyi-quartz` 只放入口 |
| 改表 | 新增迁移文件，不改已合并的迁移。编号用本线号段，见 `docs/tasks/batch-02.md` |

变动类型、平台、各种状态只定义一处，经公共字典下发，页面不写死。

## 开发流程

- 先开分支，再开 PR，CI 通过后合并，合并前同步 `main`。
- 涉及资金和归因的改动（`ledger`、`settlement`、`withdraw`、`attribution`）用真实 MySQL 测试，并由没写这段代码的人评审。其他改动 CI 过了就能合并。
- 合并或做出决定后更新 `docs/progress.md`。
- 文档有矛盾或没写的业务决定，在 PR 里问，不要自己选。技术写法自己定。
