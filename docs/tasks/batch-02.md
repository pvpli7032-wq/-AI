# 第二批任务：业务主线

原则：默认在基座现有代码上增加。替换或删除旧模块的条件见 `AGENTS.md`，没写进任务单的一律不删。业务依据是 `docs/plan.md`，本文件只定每条线做什么、能改什么、怎么验收。开工前读 `AGENTS.md`。

## 开工条件和顺序

| 轮次 | 线 | 内容 | 开始条件 |
|---|---|---|---|
| 第 4 轮 | 线 8 契约 | 接口、模型、表 | 线 4、线 5、线 6 全部合并 |
| 第 5 轮 | 线 9 查券归因 | 解析、查佣金、转链、PID 池、归因、无主订单、邀请绑定 | 线 8 合并 |
| 第 5 轮 | 线 10 订单 | 同步、回补、原始数据、状态机 | 线 8 合并 |
| 第 5 轮 | 线 11 规则账务 | 规则、返利、奖励、冻结、追回、台账 | 线 8 合并 |
| 第 5 轮 | 线 12 提现对账 | 提现、风控、对账、敞口、告警 | 线 8 合并 |
| 第 5 轮 | 线 13 页面 | 小程序和后台新页面 | 线 8 合并，且 `design/` 下的设计规格已入库 |
| 第 6 轮 | 线 14 收尾加固 | 全流程验收、压测、上线前加固 | 第 5 轮全部合并 |

合并顺序：线 8 最先。第 5 轮不分先后，一次合并一个，合并前同步 `main` 并重跑验收。线 11 和线 12 动资金，合并前都要过专门评审。

每条线完成后必须开 PR，不要直接合并。

## 共同约定

- **包路径：** 业务代码放 `ruoyi-system/src/main/java/com/ruoyi/system/cps/<业务包>/`，业务包名见 `plan.md` 第 5.1 节。联盟适配放 `ruoyi-union-sdk/src/main/java/com/ruoyi/union/adapter/`。小程序接口放 `ruoyi-admin` 的 `web/controller/miniapp/`，后台接口放 `web/controller/cps/`。定时任务放 `ruoyi-quartz` 的 `task/`，写法与 `RyTask` 一致。
- **依赖方向：** `order → attribution → settlement → ledger`，只允许单向调用。线 9～12 只通过线 8 定的接口互相调用。要改接口时单独提 PR，不在自己的线里改。
- **只有 `ledger` 能改余额。** 其他代码一律调线 8 定义的入账接口。
- **用户身份只从登录态取。** 新接口不接收 `userId`、`pid`。
- **数据来源：** 官方 SDK 未到位，占位 SDK 不能发请求。查券和订单优先接大淘客的现有请求类（`ruoyi-union-sdk/.../dtk/api/request/`）。公司 2026-10-31 告知大淘客权限已具备，尚未实测。这些调用都要能用模拟实现替换，测试不调真实接口。
- **配置：** 业务数值放后台配置，种子用明显的假值。第 9 节未定的数字不要写死，也不要自己定。
- **本批没写的细节** 以 `plan.md` 对应章节为准。章节和本文件矛盾时，在 PR 里提问，不要自行选一边。

## 线 8 契约

**目标：** 把后面各线要共用的接口、模型和表一次定好。只写接口、DTO、枚举、迁移、Mapper 骨架，不写业务实现。

**迁移编号：** V400～V499。

**能改：** `ruoyi-union-sdk` 新增 `adapter` 包、`ruoyi-system` 新增 `cps` 包和对应 Mapper、`db/migration/`、`SysOrderCommission`、`SysWechatUser`、`SysUnionPlatform`、`SysAgentCommissionAccount` 的实体和 XML（只加字段），以及 `sys_order_commission` 的单行查询（改唯一键后必须同步）。

**要求：**

1. **联盟适配接口** `UnionAdapter`，按平台实现，方法见 `plan.md` 第 5.2 节：解析链接、查佣金、转链、拉订单、拉维权单、拉处罚单。
   - 用配置选择每个平台的实现：`dtk`、`official`、`mock`。`mock` 只能在测试和非生产环境启用，生产配置下启用时启动失败。
2. **统一订单行模型** `UnionOrderLine`：平台、订单号、子订单号、商品 ID、PID、归因标识、付款金额、预估佣金、实际佣金、联盟原始状态、付款时间、收货时间、结算时间。
3. **状态枚举和映射接口：** 订单状态、返利状态按 `plan.md` 第 4.5 节定义，合法路径写成一张表，线 10 和线 11 共用。各平台映射表由线 10 填。
4. **跨线接口：** `AttributionService.decide(订单行)`（线 9 实现）、`SettlementService.onOrderEvent(订单事件)`（线 11 实现）、`LedgerService.post(入账命令)`（线 11 实现，内部基于 `updateUserAccount`）、`WithdrawChannel` 三个方法（`plan.md` 第 4.9 节，线 12 实现）。
5. **表：** 新增 `plan.md` 第 5.3 节的全部 `cps_` 表，现有表按同一节补字段：
   - `sys_order_commission` 加 `sub_order_no`（NOT NULL，无子单时为空串），唯一键由 `uk_order_platform` 改为平台 + 订单号 + 子订单号。按订单号的单行查询同步改，不留 TooManyResults。
   - `sys_wechat_user` 加 `invitation_code`（唯一）和邀请人字段。第一批线 6 如已加，只补差。
   - `sys_agent_commission_account` 加 `withdrawing_amount`（提现中）。线 4 已加的字段不重复，`balance` 允许为负。
   - `sys_union_platform` 加 PID 类型：追踪、默认、屏蔽、红包。
   - `sys_config` 现有的 `commission.min.withdraw`、`commission.freeze.days` 保留，由后续任务明确沿用或替换。

**验收：** 空库执行全部迁移，应用启动，`/captchaImage` 返回 200；`./mvnw -B test` 通过；每个接口都有一个测试用的空实现能注入。

## 线 9 查券归因

**对应：** `plan.md` 第 4.1、4.2、4.10 节，阶段 2。**迁移编号：** V500～V599。

**能改：** `union/adapter/` 的实现、`cps/attribution/`、新增 `MiniappConvertController` 和无主订单、PID 池的后台接口，`MiniappDtkController`、`MiniappJdController`、`MiniappPddController`、`MiniappTbkController`（只接入新逻辑），`ISysWechatUserService` 相关（只改邀请绑定），`tbk-miniapp/api/platform-api.js`、`tbk-miniapp/utils/request.js`（只改第 11 项），`tbk-miniapp/utils/auth.js`、`MiniappAuthController`（只改第 8 项首次授权传邀请码），`tbk-miniapp/pages/index/index.js`、`tbk-miniapp/pages/goods/detail/`（只改转发不带身份和第 9 项），`WebUtils`（只改第 10 项超时），`ruoyi-quartz` 新增到期处理和重试任务类，`tbk-miniapp/app.js`（只改启动无感登录），`tbk-miniapp/pages/share/index.js`、`tbk-miniapp/pages/user/center/center.js`、`tbk-miniapp/pages/agent/apply/apply.js`、`tbk-miniapp/pages/goods/list/list.js`（只改转发的邀请码）。

**要求：**

1. **适配实现：**
   - 解析：淘宝接 `DtkParseContentRequest`、`DtkParseTaokoulingRequest`，京东接 `DtkJdLinkAnalysisRequest`。拼多多的解析方式 `plan.md` 标为待核，先核对，无现成接口时在 PR 里说明。
   - 转链：`DtkGetPrivilegeLinkRequest`、`DtkJdCommodityTransformLinkRequest`、`DtkPddTransformLinkRequest`。
   - 查佣金优先大淘客。`IHdkApiService` 现有接口能返回佣金字段时作为备用，能否返回先核对。
2. **接口：** `POST /miniapp/convert/parse`、`POST /miniapp/convert/link`。无佣金回落、比价订单提示按 `plan.md` 第 4.1 节，提示文案后台设置（第 9.1 节第 9 项）。
3. **查券记录：** 每次都写 `cps_query_log`，失败也写，区分解析失败、无佣金、佣金过低。
4. **PID 池：**
   - 单品占用、租约 48 小时可配、默认 PID 不进追踪池、PID 用完兜底进无主池。
   - 租约只对开始后的订单生效。
   - 屏蔽 PID 下的单不可认领。
   - 分配不重复（风险 14）。
   - 后台能看单品占用率。
5. **归因：** 实现 `AttributionService.decide`，优先级和规则按 `plan.md` 第 4.2 节：只追加、高优先级可改判低优先级、反向不行、已入账的改判走人工、两人提交同一单转人工。
6. **手动提交：** `POST /miniapp/orders/claim`，失败返回具体原因，订单号展示打码，时限配置。
7. **无主订单：** 池、认领、到期归平台，保留 30 天（配置）。
8. **邀请：** 绑定收口到新用户首次授权，一人一个邀请人，不能邀请自己，后台改绑留审计（风险 15）。上下级变更不影响已下单的快照。
9. **顺手收口：** 详情用默认推广位、搜索忽略 keyword、详情页只显示预计返利不显示佣金。京东 `positionId` 已由 PR #9 修复，不再处理。
10. **容量：** 查券结果缓存、按用户限流（基座 `rate-limit` 配置未引用，接上）、联盟调用超时（`WebUtils` 没有超时）、联盟接口排队。
11. **转链带登录态：** 小程序转链和商品请求现在走 `publicGet`，已登录也不带 token，后端按匿名处理，代理的单归不到本人。转链请求改成有 token 就带，未登录仍可浏览。
12. **淘口令 `url`：** `MiniappTbkController.getTpwd` 是公开接口，客户端 URL 可夹带别人的 pid。新转链流程由服务端生成链接；旧接口保留路径，不再接受自带 pid 的 URL。
13. **归因标识：** 京东用 `subUnionId` 或推广位、拼多多用 `custom_parameters` 带用户标识，淘宝走 PID 池。淘宝 `relation_id` 依赖 APP 授权，小程序不用。

**验收：**
```bash
./mvnw -B test
```
测试覆盖：
- 同一 PID 同一商品不能同时租给两人。
- 租约开始前的订单不被绑走。
- 默认 PID 的单进无主池。
- 屏蔽 PID 不可认领。
- 改判只能从低到高。
- 两人提交同一订单转人工。
- 第 4.6 节的「手动提交晚于订单到达」「无主订单到期」「他人红包截走」。
- 转发卡片不携带发卡人身份。
- 不能邀请自己。
- 已登录转链取到本人身份，未登录仍能浏览。
- 自带别人 pid 的 URL 不能生成淘口令。

## 线 10 订单

**对应：** `plan.md` 第 4.5、5.4 节，阶段 3。**迁移编号：** V600～V699。

**能改：** `union/adapter/` 里订单、维权、处罚相关方法的实现（文件和线 9 分开放），`cps/order/`，`ruoyi-quartz` 新增同步任务类，新增订单查询的小程序和后台接口。

**要求：**

1. **来源：** `DtkGetOrderDetailsRequest`（淘宝）、`DtkJdOrderQueryV2Request`（京东）、`DtkPddOrderIncrementSearchRequest`（拼多多）、`DtkTbkScRelationRefundRequest`（维权）、`DtkTbkScPunishOrderGetRequest`（处罚）。
2. **同步：** 增量窗口重叠，靠唯一键去重；每天回补；维权处罚每小时。大促可降频，积压可追赶。
3. **原始数据：** 全部写 `cps_order_raw`，只追加，能重放。
4. **状态机：** 填三家映射表。按线 8 的合法路径表流转，非法跳转写异常，不强行改。乱序到达不能回退状态。写 `cps_order_event`。
5. **映射表的待核项：** 确认收货和联盟结算是否同一时点，查不到就在 PR 里写明，用配置开关「淘宝已收货视为结算」（`plan.md` 第 4.3 节）。
6. **衔接：** 新订单行调 `AttributionService.decide`，状态变化调 `SettlementService.onOrderEvent`，调用失败可重试，不丢事件。
7. **可疑标记：** 付款到结算不足 24 小时的单标可疑，只标记。
8. **后台：** 订单列表和详情（含归因、状态历史），按平台、状态、绑定方式筛选。`GET /miniapp/orders` 我的订单，展示状态用同行四种名称，不展示内部状态码。
9. **处罚导入：** 人工导入入口，写 `cps_punish_import`。
10. **采集检查项：**
    - 分页必须翻完，单次大量订单不能只取第一页。淘宝用位置游标，京东看 `hasMore`，拼多多的翻页参数以大淘客接口文档为准，查不到就在 PR 里写明，不能猜参数名。
    - 增量和历史补采分成两种任务。
    - 过滤不属于本系统 PID、siteId 的订单。
    - 拉取失败要重试和补偿，记录失败窗口，不能只打日志。

**验收：** `./mvnw -B test`。用模拟返回测试：重复拉取不重复处理、子订单各成一行、窗口重叠不重复、乱序到达不回退、非法跳转记异常、重放原始数据结果一致、多页全部取完、失败窗口能补回、混入的他人订单被过滤。

**上线前实测：** 用公司真实账号在测试服拉一批真实订单，核对笔数和金额。步骤由人工执行，Codex 不接触真实密钥。

## 线 11 规则账务

**对应：** `plan.md` 第 4.3、4.4、4.6、4.7 节，阶段 4。**迁移编号：** V700～V799。

**能改：** `cps/rule/`、`cps/settlement/`、`cps/ledger/`，`CommissionCalculateServiceImpl`（放宽和接入，不重写），新增规则管理后台接口，钱包小程序接口。

**要求：**

1. **公式和舍入：** 按 `plan.md` 第 4.3 节。所有金额走同一个计算方法，`HALF_UP` 到分，不用 `Arith`。实际佣金有上限校验（风险 5）。京东非自营的 10% 扣减留配置项。
2. **比例规则：** 互斥，优先级定向 > 新人 > 阶梯 > 基础。佣金分档后台可配，比例 0 不能当未设置。邀请模式和佣金分档互斥，邀请人数只算有效用户。沉默回归是公开活动。
3. **奖励规则：** 第 4.4 节的表全部做，可叠加。首单强制返利只发一次。
4. **硬规则：** 保存时校验最差毛利（按舍入后金额算）、规则有版本且改规则只影响之后的单、奖励过冻结期才发。
5. **普通用户：** `calculateCommission` 放宽为没有代理记录也能走（第 9.1 节第 2 项）。失效代理不再拿钱（风险 13）。
6. **返利生命周期：** 实现 `onOrderEvent`。预估不记账，确认收货进冻结，到期入可用（风险 3）。维权暂停计时。冻结天数按金额分档（第 9.1 节第 4 项）。
7. **追回：** 第 4.6 节全部场景，包括负余额、后续返利先抵、被邀请人退款回退提成、下单数量奖回退。
8. **台账：** 实现 `LedgerService.post`，每笔一借一贷，唯一键防重，错了发冲正。提供「用流水重算余额」的校验任务。`platform_amount`、`total_commission`、`agent_user_id` 结算时写全。
9. **解冻任务：** 每小时，过滤暂停和异常。
10. **接口：** `GET /miniapp/wallet`、`GET /miniapp/wallet/flows`、`GET /miniapp/rules/activity`；后台规则管理含试算。

**验收：** `./mvnw -B test`，必须用真实 MySQL。
- `plan.md` 第 4.6 节**每一行**一个测试用例，PR 里逐行列出对应的测试方法。
- 示例算例：净佣金 18.00、返利 9.00、提成 1.80。
- 并发入账后余额等于流水重算。
- 重复事件不重复入账。
- 超过最差毛利的规则保存失败。
- 改规则后在途订单金额不变。
- 补线 7 冲正的测试：买家同时是推荐人时只冲一条、已取消订单拒绝冲正、旧流水走 `related_order_id` 的路径、冲正和退款两种先后顺序并发。

## 线 12 提现对账

**对应：** `plan.md` 第 4.8、4.9 节，阶段 5。**迁移编号：** V800～V899。

**能改：** `cps/withdraw/`、`cps/recon/`、`cps/risk/`，`MiniappUserController` 的提现方法，新增提现、对账、敞口的后台接口，`ruoyi-quartz` 新增对账、敞口、健康检查任务类。

**要求：**

1. **提现单：** 申请、审核、打款、失败退回。「已审核」和「已打款」分开。负余额、封禁、敞口超限不能提现。
2. **旧接口：** `MiniappUserController.java:90-103` 接到真实提现单，路径保留；`:109-122` 返回真实记录。同时提供 `POST`、`GET /miniapp/withdrawals`。
3. **提现号：** 全局唯一生成器，不用若依 `Seq`。提现号就是打款幂等号，结果不明只查询，不换号重发。
4. **审核：** 三层门槛按第 4.9 节，全部配置，填 0 关闭，**任一触发转人工**（第 9.1 节第 8 项）。
5. **门槛配置：** 第 4.9 节列的金额、次数、日期、上限、整数提现等全部做成配置，通道限额和本地限额分层校验。同一收款账号每日次数也做成配置，填 0 关闭。
6. **通道：** 只做模拟通道，覆盖成功、失败、处理中、结果不明。真实通道后接。
7. **对账：** 联盟报表、联盟到账、系统台账、打款记录四边核对，差异逐条写 `cps_recon_diff`。
8. **敞口和告警：** 按第 4.8 节。授权失效告警必选且不可关。
9. **防刷：** 第 4.10 节的信号命中后暂停奖励转人工。

**验收：** `./mvnw -B test`，真实 MySQL：
- 同一用户并发提现不超付。
- 结果不明时不重发，重复回调结果不变。
- 打款失败余额退回。
- 负余额、封禁、敞口超限时被拒。
- 任一门槛触发转人工。
- 第 4.6 节「已提现后订单失效」。

## 线 13 页面

**对应：** `plan.md` 第 5.5、5.6 节，阶段 6。

**开始条件：** 设计规格在 `design/` 入库。规格单独安排，Codex 不自己设计。

**能改：** `tbk-miniapp/`、`ruoyi-ui/src/views/`、`ruoyi-ui/src/api/`、`ruoyi-ui/src/router/`。不改后端。接口有缺失时在 PR 里提，不自己加。

**要求：**

- **小程序：** 首页粘贴入口（用户主动粘贴，不读剪贴板）、查券结果、我的订单、提交订单号、钱包、提现、邀请。现有 10 个页面全部保留。
- **提示：** 第 4.2 节的绑定提示全部要有，提示文案从接口取。
- **转发：** 卡片不带发卡人身份。
- **后台：** 规则管理和试算、订单、无主订单、处罚导入、提现审核、用户风控、PID 池、对账差异、敞口和运营看板。菜单写迁移脚本（V900～V999）。权限键用第一批统一后的写法。

**验收：**
```bash
cd ruoyi-ui && npm install && npm run build:prod
```
小程序在开发者工具编译通过。PR 里列出每个新页面逐页检查结果。开发者工具跑不了时写明。

## 线 15 删除代理申请模块

**前置：** 线 9、10、11 合并，且线 11 的佣金计算不再依赖 `sys_tbk_agent_apply`。线 13 排在本线之后。开工前在 `.github/pr-scope.conf` 登记范围，迁移用 V1100～V1199。

**删除：** `SysTbkAgentApply` 的实体、Mapper、Service、后台控制器，小程序 `pages/agent/apply/`，以及各控制器和页面里的代理申请、`isAgent`、`agentInfo` 分支。

**保留：** `sys_agent_commission_account`（全体用户账户）、`sys_agent_commission_config`。`sys_agent_union_relation` 和 `sys_tbk_agent_apply` 表先保留，删表只写脚本，由用户确认后执行。

**验收：** `./mvnw -B test`；普通用户查券、转链、下单、返利、提现、邀请全流程不受影响；全仓库搜不到已删除类的引用。

## 线 14 收尾加固

**迁移编号：** V1000～V1099。

**要求：**

1. **全流程：** 用模拟联盟数据走通粘贴、转链、订单、归因、冻结、入账、提现、退款追回。
2. **容量：** 按 2000 人同时在线（第 9.1 节第 11 项）调整 Druid、Redis、Tomcat 连接参数，`RedisCache` 写入带 TTL，提交压测脚本和结果。
3. **第 13 节「其他发现」和第 2.3 节里第一批没覆盖的项：**
   - `SysConfigController` 无权限注解、`TestController` 残留、Druid 白名单、`request.js` 不看业务 code、日志打印 token、session_key 和 token 明文、`IpUtils` 取 IP、淘宝网关 http。
   - 后台 `jsencrypt.js` 私钥、`login.vue` 预填账密、首页假数据、`/register` 路由、`ruoyi-ui` 标题。
   - 大淘客 key 单例（`DtkApiClient.java:24-36`、`HdkApiServiceImpl.java:36-52`）。
   - 退款按钮不可达。
   - `SysUnionPlatformController.java:180` 的 `changeStatus` 日志不记录密钥（线 7 遗留）。
   - `MiniappTbkController` 公开接口 `/agent/{invitationCode}` 返回代理 `appKey`、`adzoneId`，先核对小程序是否使用，不用就去掉。
4. **对外文案：** about 页和协议里的 40%/5%、最低提现、结算日、占位邮箱，改成从配置取或先下线承诺，不写未定的数字。

**验收：** `./mvnw -B test`、`ruoyi-ui` 构建、压测原始结果、逐项列出第 3 项的处理情况。

## 上线前必须有答案，不阻塞开发

- 用真实大淘客 key 实测解析、订单、维权、处罚接口。
- `plan.md` 第 7 节的其余项和第 9 节未定的数字。
- 三家官方 SDK（T05）。

## 第 13 节风险归属

| 风险 | 归属 |
|---|---|
| 1、2、4、16 | 线 4 |
| 6、9、11 的比例回退、17 的「暂未开放」 | 线 7（T04 剩余项） |
| 7 | 线 5（PR #3） |
| 18 的固定密码 | 线 5 |
| 8、10 | 线 6 |
| 3、5、11 的比例校验、12、13 | 线 11 |
| 14、15 | 线 9 |
| 17 的真实提现 | 线 12 |
| 19 | 第 9.1 节第 2 项：线 8、线 11 |

风险 18 里的大淘客 key 单例放线 14，错误外泄在线 14 第 3 项一起查。
