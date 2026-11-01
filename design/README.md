# 设计规格总览（线 13 页面）

依据：`docs/plan.md` 第 4.1、4.2、4.5、4.9、4.10、5.5、5.6 节，`docs/tasks/batch-02.md` 线 13。
实作方：Codex（线 13）。本目录只是规格，不含生产代码。

文件：
- `design/README.md`：本文件，通用规范和页面索引。
- `design/miniapp.md`：小程序 7 个新页面、首页和用户中心的改动。
- `design/admin.md`：后台规则和各模块规格，末尾有待确认事项。
- `design/admin-prototype/index.html`：后台可点击原型，2026-11-01 已确认，浏览器直接打开。

## 1. 目标与用户

| 端 | 用户 | 要完成的事 |
|---|---|---|
| 小程序 | 普通买家（会员，说大白话，不懂联盟、佣金） | 粘贴商品链接或口令，看到能返多少钱，去下单；查订单进度；看钱包、提现；邀请朋友 |
| 后台 | 运营、财务、客服 | 配规则，处理无主订单和异常，审核提现，看风险和对账，看大盘 |

## 2. 约束（实作必须遵守）

1. 首页粘贴入口：用户主动粘贴到输入框，或点「粘贴」按钮后触发。不得在 `onLoad`、`onShow` 里调用 `wx.getClipboardData`。
2. 展示状态只用四个名字：待收货、冻结中、已结算、已退款。不显示内部状态码、不显示英文枚举。
3. 所有提示文案（绑定提示、无佣金、比价订单、失败原因、活动说明）从接口取。前端只放「兜底文案」，且兜底文案只用于接口完全失败时。
4. 转发卡片（查券结果、商品、订单等任何页面的 `onShareAppMessage`）：path 不带 userId、昵称、头像、邀请码、PID。标题和图片用通用文案。邀请页的邀请卡是唯一例外，只带邀请码，见 miniapp.md 的 M7 和本文第 9 节决定 1。
5. 详情和查券结果只显示「预计返利」，不显示佣金、佣金率、服务费、比例。
6. 不写未定的数字（返利比例、冻结天数、最低提现额、N 分钟、租约小时等）。这些值全部来自接口或配置，设计稿里用占位符 `{配置:xxx}`。
7. 不新增 plan 没有的功能。不做：团队多级、代理 PID、尾号绑定、淘宝授权、手续费展示、实名认证页（未在 plan 内定义）、深色模式。
8. 后台只用 Element UI 2.x（Vue 2），沿用 `ruoyi-ui/src/views/system/commission/` 的写法（`app-container`、搜索表单 inline、`el-table`、`pagination`、`right-toolbar`、`v-hasPermi`、`el-dialog append-to-body`）。不引入新组件库、新图表库以外的依赖（看板图表用已有依赖 echarts，见 admin.md 顶部）。
9. 现有小程序页面保留，只有 `pages/agent/apply/` 由线 15 删除。现有 `pages/goods/list/list`（tab「收益」）、`pages/share/index` 不改动逻辑。后台旧订单页 `views/system/commission/order/index.vue` 保留，不删不改。

## 3. 现有风格（读自代码）

小程序：
- 页面背景 `#f5f5f5`；卡片白底、圆角 12rpx、内边距 20rpx、外边距 20rpx、阴影 `0 2rpx 8rpx rgba(0,0,0,.05)`（`app.wxss` `.card`）。
- 主色渐变紫 `#667eea → #764ba2`（用户中心、首页横幅、代理卡）；蓝渐变 `#4A90E2 → #5BA0F2`（首页）；tabBar 选中色 `#ff6600`。
- 功能色（`app.wxss`）：primary `#1890ff`、success `#52c41a`、warning `#faad14`、danger `#ff4d4f`；tag 底色 `#e6f7ff / #f6ffed / #fffbe6 / #fff2f0`。
- 基础字号 28rpx，标签 24rpx，次要灰 `#999`。
- 通用类已有：`.card .list-item .loading .empty .tag-* .btn-* .flex-between .mt-* .mb-*`。新页面优先复用，不重写。
- 提示用 `components/custom-toast`（已有组件）；弹窗沿用首页 `tpwd-modal` 的样式思路。
- 用户中心菜单 `menuList` 里已有 `/pages/team/list`、`/pages/withdraw/list`，这两个路径在 `app.json` 里不存在（读自 `center.js:19-47`、`app.json`）。处理方式见 miniapp.md M8，归线 13。

后台：Element UI 默认主题；金额用 `¥` 前缀；状态用 `el-tag`。

## 4. 通用规范（小程序新页面）

### 4.1 令牌（新页面使用，延续现有取值）

| 项 | 值 |
|---|---|
| 页面背景 | `#f5f5f5` |
| 卡片 | 白底，圆角 12rpx，内边距 24rpx，卡片间距 20rpx，阴影同 `.card` |
| 正文 | 28rpx / `#333` |
| 次要文字 | 24rpx / `#666`（现有 `#999` 对白底对比约 2.8:1，不达 AA，新页面次要文字改用 `#666`；`#999` 仅用于占位符和禁用） |
| 标题 | 32rpx 加粗 / `#333` |
| 金额大字 | 48rpx 加粗，数字用 `font-variant-numeric: tabular-nums` |
| 返利强调色 | 大字（>= 36rpx 加粗）可用 `#ff6600`；小字号返利文字用 `#d4380d`（对白底约 5:1，实作后用对比度工具核对） |
| 主按钮 | 渐变 `#667eea → #764ba2`，白字 32rpx，高 88rpx，圆角 44rpx |
| 次按钮 | 白底，边框 2rpx `#667eea`，字 `#667eea` |
| 禁用按钮 | 底 `#e5e5e5`，字 `#999`，不可点 |
| 危险文字 | `#cf1322`（小字）/`#ff4d4f`（图标、边框） |
| 触控目标 | 不小于 88rpx 高、至少 88rpx 宽（约 44px） |
| 页面左右边距 | 24rpx |
| 底部安全区 | 固定底栏加 `padding-bottom: env(safe-area-inset-bottom)` |

深色模式：不做。小程序和后台均只提供浅色主题，颜色令牌不设深色取值。

### 4.2 展示状态与颜色（订单、返利通用）

| 展示名 | tag 样式 | 说明文案来源 |
|---|---|---|
| 待收货 | `tag-primary`（蓝） | 接口 |
| 冻结中 | `tag-warning`（黄，字改 `#ad6800` 保证对比度） | 接口（含到期日） |
| 已结算 | `tag-success`（绿，字 `#237804`） | 接口 |
| 已退款 | `tag-danger`（红，字 `#cf1322`） | 接口 |

标签必须带文字，不得只靠颜色区分。接口返回的状态名不在这四个之内时：显示接口给的文字，样式用灰（`#f5f5f5` 底 `#666` 字）；不自行翻译状态码。该情形列入接口缺口 A1。

### 4.3 金额显示

- 保留两位小数，`¥` 前缀，千分位不加。
- 负数显示 `-¥12.30`，红色 `#cf1322`。
- 接口返回字符串金额则原样显示，前端不做浮点运算（金额加减、汇总都由接口给出）。

### 4.4 页面状态模板（每个页面都要有）

| 状态 | 表现 |
|---|---|
| 加载（首次） | 骨架或 `.loading` 居中「加载中…」；不出现空状态 |
| 加载更多 | 列表底部「加载中…」，到底显示「没有更多了」 |
| 空 | `.empty`：图标 + 一句话 + 可选按钮，文案见各页 |
| 失败 | 整页：图标 + 接口错误信息（没有则用「网络不太好，请再试一次」）+「重新加载」按钮；列表翻页失败：底部「加载失败，点这里重试」 |
| 未登录 | 整页提示：图标 + 「请先登录」+「去登录」按钮，跳 `pages/auth/login/login`；登录完成回到来源页（见 miniapp.md 通用规则） |
| 下拉刷新 | `app.json` 已全局开启 `enablePullDownRefresh`，新页面都要处理并在完成后 `wx.stopPullDownRefresh` |
| 重复点击 | 提交类按钮点击后进入 loading 并禁用，直到接口返回 |

## 5. 无障碍（小程序）

- 所有可点元素有文字或 `aria-label`（小程序用 `aria-role`、`aria-label` 属性）。图标按钮必须有文字标签。
- 正文对比度 >= 4.5:1，大字 >= 3:1。渐变底上只放大字（>= 36rpx 加粗）或加半透明深色衬底。
- 状态不只靠颜色。
- 输入框有可见标签，不只靠 placeholder。
- 错误信息在输入框下方文字显示，同时用 toast 不是唯一反馈。
- 页面标题（`navigationBarTitleText`）每页都设。
- 字号支持系统大字体：不用固定高度裁切文字，用 `min-height`。

## 6. 无障碍与键盘（后台）

- 沿用 Element UI 默认的焦点样式，不去掉 outline。
- 表单项必须有 `label`；表格操作列的图标按钮必须带文字。
- 对话框打开后焦点进入对话框，关闭后回到触发按钮（Element 默认行为，不要关掉 `:close-on-click-modal` 以外的默认行为；危险操作对话框建议 `:close-on-click-modal="false"`）。
- 状态用 `el-tag` 带文字。
- 搜索表单支持回车查询（`@keyup.enter.native`）。

## 7. 页面索引

### 小程序（详见 `miniapp.md`）

| 编号 | 页面 | 路径建议 | 类型 | 入口 |
|---|---|---|---|---|
| M1 | 首页粘贴入口 | `pages/index/index`（现有页面内新增区块） | 改 | tab「首页」 |
| M2 | 查券结果 | `pages/convert/result/result` | 新 | M1 点击「查返利」 |
| M3 | 我的订单 | `pages/order/list/list` | 新 | 用户中心「我的订单」、钱包页 |
| M4 | 提交订单号 | `pages/order/claim/claim` | 新 | M2 提示区、M3 页底部、用户中心 |
| M5 | 钱包 | `pages/wallet/index/index` | 新 | 用户中心「我的钱包」 |
| M6 | 提现 | `pages/wallet/withdraw/withdraw` | 新 | M5「提现」按钮、提现记录在同页 |
| M7 | 邀请 | `pages/invite/index/index` | 新 | 用户中心「邀请好友」 |
| M8 | 用户中心改动 | `pages/user/center/center`（现有） | 改 | tab「我的」 |

路径是建议值，需要同步加入 `app.json` 的 `pages`。tabBar 不增不减（保持 3 个 tab）。

### 后台（详见 `admin.md`）

权限键统一为 `system:commission:*`，沿用现有写法（例：`system:commission:order:export`，见 `order/index.vue:51`）。新模块写成 `system:commission:<模块>:<动作>`，动作沿用若依惯例（`list`、`query`、`add`、`edit`、`remove`、`export`），模块段由实作按页面命名。

| 编号 | 模块 | 菜单位置 | 权限键前缀 |
|---|---|---|---|
| A1 | 试算、校验和版本 | 功能管理 > 返利设置（待确认） | `system:commission:*` |
| A2 | 订单（列表、详情） | 订单管理 > 总订单 | `system:commission:*` |
| A3 | 无主订单 | 订单管理 > 无主订单 | `system:commission:*` |
| A4 | 处罚导入 | 订单管理 > 处罚导入 | `system:commission:*` |
| A5 | 提现审核 | 提现管理 > 提现列表 | `system:commission:*` |
| A6 | 用户风控 | 用户管理 > 用户风控 | `system:commission:*` |
| A7 | PID 池 | 联盟管理 > PID 池 | `system:commission:*` |
| A8 | 对账差异 | 提现管理 > 对账差异 | `system:commission:*` |
| A9 | 敞口和运营看板 | 数据看板 > 敞口看板、运营看板 | `system:commission:*` |

完整菜单和其余栏目见 `admin.md`“菜单”一节。

## 8. 验收总清单（通用部分）

各页具体验收见两份文件末尾。通用项：

1. 全部新页面已登记进 `app.json`，tabBar 仍为 3 项，除线 15 删除的代理申请页外，原页面文件未删。
2. 任何页面在 `onLoad`、`onShow` 中没有读剪贴板（全局搜索 `getClipboardData`，只允许出现在用户点击「粘贴」的处理函数里）。
3. 全局搜索，界面文案中没有出现内部状态码或英文枚举（`pending`、`confirmed`、`settled`、`refunded`、`FROZEN` 等）。
4. 所有 `onShareAppMessage` 返回的 path 不含 `userId`、`pid`、`nick`、`avatar`；邀请页除外且只带接口给的邀请码。
5. 查券结果和订单详情中没有「佣金」「佣金率」「服务费」字样，只有「预计返利」。
6. 没有硬编码的比例、天数、最低提现额、N 分钟；设计稿里的占位符都由接口或配置提供。
7. 每个新页面都实现了加载、空、失败、未登录四种状态，并被手动验证（PR 里逐页列出）。
8. 次要文字不使用 `#999` 在白底上。
9. 后台所有新页面使用 Element UI，无新增 npm 依赖。
10. `cd ruoyi-ui && npm run build:prod` 通过；小程序在开发者工具编译通过。

## 9. 已定决策与跨端依赖

已定决策（实作按此执行，不再讨论）：

1. 转发不带身份。商品、查券结果、订单类转发一律不带身份；邀请页的邀请卡只带接口返回的邀请码（`GET /miniapp/invite`），不带 userId、昵称、头像。
2. 查券时就分配 PID，查券时就转链。M2 解析成功后立即请求转链，点按钮只复制，不再在点击时才转链。
3. 收款信息字段等打款商户选定后再定。当前规格不定字段，页面留位，不编造字段（见 miniapp.md M6、接口缺口 E1）。
4. 提现显示状态：审核中、打款中、已打款、打款失败、审核驳回，对应关系见 `admin.md`“提现”一节。
5. 后台权限键用 `system:commission:*`。
6. 后台旧订单页保留，新订单页另增。
7. 订单号显示：后台不打码，小程序打码。
8. 不做深色模式。
9. 首页粘贴区放在代理入口之后。
10. 首页商品卡「预估佣金」改为「预计返利」，归线 13 实作。
11. 用户中心缺失页面路径和不存在的 `/images/share-bg.png` 归线 13 处理，做法见 miniapp.md M7、M8。

跨端依赖：

- 接口字段名尚未确定（`/miniapp/convert/*` 等接口代码在仓库里尚不存在，已查 `ruoyi-admin/.../controller/miniapp` 只有旧的 `/orders`、`/withdraw/apply`、`/withdraw/list`）。本规格用「语义字段」描述，实作时以线 8 契约的 DTO 为准。见两份文件末尾的接口缺口。
