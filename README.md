# CPS 返利小程序

淘客返利微信小程序系统。用户粘贴商品链接，系统查询联盟佣金并生成带归因的推广链接，订单确认收货后按规则返利。

- 方案：[docs/plan.md](docs/plan.md)
- 开发约定：[AGENTS.md](AGENTS.md)
- 任务单：[docs/tasks/](docs/tasks/)

## 目录

| 目录 | 内容 |
|---|---|
| `ruoyi-admin`、`ruoyi-framework`、`ruoyi-system`、`ruoyi-common`、`ruoyi-quartz`、`ruoyi-generator` | 后端（Java 21、Spring Boot 4） |
| `ruoyi-union-sdk` | 联盟接口封装 |
| `ruoyi-ui` | 后台页面（Vue 2、Element UI） |
| `tbk-miniapp` | 微信小程序 |
| `sql` | 数据库脚本 |

## 配置

所有密钥通过环境变量传入，变量清单见 `.env.example`。不要把真实值提交进仓库。

## 许可

基于 [SkaFull/cps](https://github.com/SkaFull/cps) 和若依开发，遵循 MIT 协议，见 [LICENSE](LICENSE)。
