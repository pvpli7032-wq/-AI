# 开发约定

接手先读 `docs/handover.md`。业务依据见 `docs/plan.md`，任务见 `docs/tasks/`。

## 目录结构

遵守项目目录结构，不要在下面约定的位置以外新建文件或写代码，也不要新建顶层目录。

| 内容 | 位置 |
|---|---|
| 联盟接口 | `ruoyi-union-sdk/src/main/java/com/ruoyi/union/` |
| 业务逻辑 | `ruoyi-system/src/main/java/com/ruoyi/system/cps/<业务包>/` |
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
