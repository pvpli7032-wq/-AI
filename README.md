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

## 本地启动（空库）

需要完整 JDK 21（`java -version`、`javac -version` 均为 21）、Docker Compose，以及 Node/npm。本地验证使用 Node 24.19 / npm 11.9；现有 Vue CLI 4 / Webpack 4 在现代 Node 上需要下面的构建参数。无需全局替换 Java 或 Node。

以下在仓库根目录执行。示例只用于本机离线测试，使用 3307/6380 避开常见的数据库端口；如端口已占用，同时修改端口变量和 JDBC/Redis 地址。已有 `.env` 时保留它，按 `.env.example` 补齐配置；下面命令拒绝覆盖已有文件。

```bash
(
umask 077
set -o noclobber
cat > .env <<'ENV'
TOKEN_SECRET='local-test-only-token-secret-do-not-deploy-0123456789'
DB_USERNAME='cps_ci'
DB_PASSWORD='ci-only-app-not-for-production'
DRUID_USERNAME='local-test-admin'
DRUID_PASSWORD='local-test-only'
TBK_APP_KEY='local-test-placeholder'
TBK_APP_SECRET='local-test-placeholder'
TBK_ADZONE_ID='0'
PDD_CLIENT_ID='local-test-placeholder'
PDD_CLIENT_SECRET='local-test-placeholder'
PDD_POSITION_ID='local-test-placeholder'
JD_APP_KEY='local-test-placeholder'
JD_APP_SECRET='local-test-placeholder'
JD_SITE_ID='0'
JD_POSITION_ID='0'
DTK_APP_KEY='local-test-placeholder'
DTK_APP_SECRET='local-test-placeholder'
DTK_ADZONE_ID='0'
WX_MINIAPP_APPID='wx-local-test-placeholder'
WX_MINIAPP_SECRET='local-test-placeholder'
MYSQL_ROOT_PASSWORD='local-test-only-root-do-not-deploy'
MYSQL_DATABASE='cps_test'
MYSQL_PORT='3307'
REDIS_PORT='6380'
SPRING_DATASOURCE_DRUID_MASTER_URL='jdbc:mysql://127.0.0.1:3307/cps_test?useUnicode=true&characterEncoding=utf8&useSSL=true&serverTimezone=GMT%2B8'
SPRING_DATA_REDIS_HOST='127.0.0.1'
SPRING_DATA_REDIS_PORT='6380'
SPRING_DATA_REDIS_DATABASE='1'
SPRING_DATA_REDIS_PASSWORD=''
SERVER_ADDRESS='127.0.0.1'
SERVER_PORT='8080'
ENV
)
```

### 1. 启动数据库和 Redis

```bash
docker compose up -d --wait --wait-timeout 120
docker compose ps
```

两个服务均应为 healthy。它们只发布本机端口，数据由当前 Compose 项目的命名卷保存，不使用其他项目的库。此时 MySQL 只有空的 `cps_test` 数据库，不要手动导入旧 SQL，迁移由应用启动执行。

### 2. 打包并启动后端

```bash
bash scripts/build-sdk-stub.sh
./mvnw -B package
set -a
. ./.env
set +a
CPS_LOCAL_DIR=$(mktemp -d "${TMPDIR:-/tmp}/cps-local.XXXXXX")
mkdir -p "$CPS_LOCAL_DIR/uploads"
cat > "$CPS_LOCAL_DIR/logback.xml" <<'XML'
<configuration>
    <appender name="console" class="ch.qos.logback.core.ConsoleAppender">
        <encoder><pattern>%level %logger{36} - %msg%n</pattern></encoder>
    </appender>
    <root level="info"><appender-ref ref="console" /></root>
</configuration>
XML
java -Djava.awt.headless=true \
  -Dlogback.configurationFile="$CPS_LOCAL_DIR/logback.xml" \
  -Dlogging.config="$CPS_LOCAL_DIR/logback.xml" \
  -jar ruoyi-admin/target/ruoyi-admin.jar \
  --ruoyi.profile="$CPS_LOCAL_DIR/uploads"
```

应用从空库执行 V1（43 张基座表及明确的测试种子）、V2（5 张表新增 19 列）、V3（修正佣金金额精度和结算状态），随后出现 `Started RuoYiApplication`。Flyway 使用与应用相同的数据库地址、账号和密码，通过独立连接迁移，避免 Druid 将可选权限探测失败误判为连接失效；不需要授予其他数据库的读取权限。

`includeSystemScope` 已将 5 个 SDK JAR 放进最终程序，不需要额外 classpath。占位 SDK 仅用于编译和离线测试，不能上线；真实接口联调先完成 T05。两个日志参数用于覆盖原有固定日志路径的早期初始化和 Spring Boot 初始化，上传文件也写入当前临时目录，不改动应用源配置。

在另一个终端验证：

```bash
curl --fail --silent http://127.0.0.1:8080/captchaImage
```

应得到 HTTP 200、JSON `code=200`、非空 `uuid` 和 `img`。CI 还会校验 JPEG 内容及 Redis 验证码缓存，并检查 Flyway V1/V2/V3 成功。重复启动会校验已执行迁移，不重新导入种子；不要修改已经合并并执行的迁移。

### 3. 打开后台登录页

保留后端终端运行，在新终端进入 `ruoyi-ui`：

```bash
npm install --legacy-peer-deps --package-lock=false
BROWSER=none port=18081 NODE_OPTIONS=--openssl-legacy-provider npm run dev -- --host 127.0.0.1
```

在本机浏览器打开 `http://127.0.0.1:18081`，应看到账号、密码和验证码输入框及验证码图片。开发服务通过 `/dev-api` 转发到本机后端 8080。新库仅含明确的测试账号 `admin` / `LocalTest123!`；页面原有预填密码不是该测试密码，需要替换。V1 开启初始密码修改提示，部署后必须立即更改 admin 口令，具体见 `docs/deploy-test.md` 第 7 节。本机步骤已验证完整登录页和真实验证码请求；其他业务页面的修复按并行任务执行。

`node_modules`、构建输出、环境文件和日志都是本地生成物，不加入 PR。只停止本次启动的前台进程；需要暂停数据服务时执行 `docker compose stop`。不要删除已有项目的数据卷来处理迁移错误。

## 数据库版本与部署

- [字段差异清单](sql/schema-differences.md) 覆盖 43 张表、实体与 Mapper；区分缺列、查询投影和已有的读写不一致。旧会话/登录日志的新旧命名冲突仍需后续认证任务处理，补列不代表这些功能已修好。
- V1 采用 `sql/cps-202604112202-mt.sql` 的全部表，保留旧 SQL 文件；不复制历史用户、日志、联盟凭据或财务配置。返利比例默认值使用中性 `0.00`，不代表生产业务配置。
- `out-of-order=true` 支持并行迁移合并；线 3 使用 V1～V99，后续线使用各自编号段。`baseline-on-migrate=false`、`clean-disabled=true`，已有非空且未受 Flyway 管理的库需要单独核对接入，不能自动覆盖。
- [宝塔测试部署说明](docs/deploy-test.md) 由管理员人工执行，使用独立项目目录、Java、数据库及 Redis 库；只修改本项目站点的 Nginx 配置。

本分支评审前的 V1/V2 已在本地验收库执行；评审按要求调整尚未合并的 V1 初始密码提示种子，V2 保持不变并新增 V3。旧验收库会触发 V1 校验和不匹配，应保留旧库并使用新的独立空库验收；不使用 Flyway repair、删除历史或跳过校验。其他环境若已执行旧 V1，部署前须先协调迁移方案。

## 许可

基于 [SkaFull/cps](https://github.com/SkaFull/cps) 和若依开发，遵循 MIT 协议，见 [LICENSE](LICENSE)。
