# 第一批任务：修通基座

按顺序执行，上一个合并后再开始下一个。每个任务单独开 PR。开工前读 `AGENTS.md`。

## T00 环境探测和 CI

**目标：** 确认当前环境能编译、能跑 MySQL 测试，建立 CI。

**范围：** 新增 Maven Wrapper、`.github/workflows/ci.yml`。不改业务代码。

**要求：**
- CI 使用 Java 21，提供 MySQL 8 和 Redis 服务。
- 此时全量编译会因缺 SDK jar 失败，CI 先只编译 `ruoyi-common`。
- 在 PR 说明里报告当前环境：Java 版本、Maven 是否能下载依赖、能否启动 MySQL 和 Redis、是否有 Docker、能否联网。

**验收：**
```bash
./mvnw -B -pl ruoyi-common -am compile
```

## T01 去掉官方 SDK jar 依赖

**目标：** 全量编译通过。三家联盟 SDK jar 不能进公开仓库，改成 HTTP 签名直连。

**范围：** `ruoyi-union-sdk`，以及引用这些 SDK 类的文件：`ruoyi-system` 的 `TbkSdkService`、`JdSdkService`、`PddApiServiceImpl`、`SysAgentUnionRelationServiceImpl`，`ruoyi-admin` 的 `TbkController`。

**不能动：** 对外接口的路径和入参；大淘客、好单库模块。

**要求：**
- 删除 `ruoyi-union-sdk/pom.xml` 里的 system scope 依赖，以及只为旧 SDK 引入的 `javax.servlet-api`、`jackson-*-asl`。
- `TbkModule`、`JdModule`、`PddModule` 保留通用 `execute(params)`，内部改为 HTTP 请求和各平台签名。
- 超时和错误码统一处理，日志不输出密钥。
- 签名算法以三家官方文档为准。无法确认的写进 PR 说明，不要猜。

**验收：**
```bash
./mvnw -B -DskipTests compile
./mvnw -B -pl ruoyi-union-sdk -am test
```
三家各有签名单元测试，期望值来自官方示例或独立计算，不能从被测代码复制。真实调用由人工联调。

## T02 统一数据库基线

**目标：** 空 MySQL 能一键建库，应用能启动。

**范围：** `sql/`、新增 Flyway 依赖和迁移目录。

**要求：**
- 把现有脚本合并成一个 V1 基线，解决 `sys_tbk_agent_apply` 两版定义冲突，以 `sql/cps-202604112202-mt.sql` 为准。
- 基线只含若依基础表、菜单字典、Quartz 表和假数据，去掉操作日志和登录日志数据。
- 补齐代码用到但建表语句缺失的字段，或记录在 PR 里说明由后续任务替换。
- 提供 `docker-compose.yml` 启动 MySQL 和 Redis，用于本地开发。

**验收：** CI 用空库执行迁移，以测试用环境变量启动 `ruoyi-admin`，请求 `/captchaImage` 返回 200。

## T03 裁剪和收口

**目标：** 去掉用不到的模块，关闭生产调试入口。

**范围：** `giteeai`、`hdk`、抖音适配、相关配置和小程序调用。

**要求：**
- 删除前列出全部引用，确认无业务依赖。
- 生产配置关闭 swagger 和 Druid 控制台。
- 删除 `ruoyi-ui/src/utils/jsencrypt.js` 里的内置密钥对，改成部署时配置，前端不保留私钥。

**验收：**
```bash
./mvnw -B test
```
`ruoyi-ui` 能构建；后台登录流程可用。

## T04 C 端用户和鉴权

**目标：** 小程序用户和后台账号分开，身份只来自登录态。

**范围：** 新建 `cps-core` 模块；新增 `cps_user`、`cps_invite_relation` 表；`MiniappAuthController`；`SecurityConfig`；小程序 `app.js`、`utils/auth.js`、分享相关页面。

**要求：**
- 去掉 `MiniappAuthController.java:195` 写死的密码，小程序用户不再写入 `sys_user`。
- C 端 token 不能用于后台接口，后台 token 不能用于 C 端接口。
- 邀请码只在新用户首次授权时绑定，统一参数名，修复 `invitationCode` 和 `inviteCode` 不一致。
- 实现 `/miniapp/auth/refresh`，或删除前端调用并改成重新登录。
- 转链接口要求登录，从登录态取用户，不再接收 `userId`、`pid`。

**验收：** 集成测试覆盖：
- 新用户首次登录带邀请码，绑定成功
- 老用户带邀请码，不绑定
- 自己邀请自己，不绑定
- C 端 token 访问后台接口，返回 401 或 403
- 未登录调用转链，返回 401
- 请求里伪造 userId，不影响实际用户
