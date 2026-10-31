# 宝塔测试环境手动部署

本文由服务器管理员人工执行，不会自动操作服务器。所有 `<...>` 都是待替换的占位符；路径应填写绝对路径，域名、端口、账号和数据库名应使用本项目的实际配置，不要原样执行。

当前联盟 SDK 可以是 T01 生成的占位包。带占位包的程序只能用于隔离的离线测试，不接真实用户、不调用真实联盟接口，不能上线承载真实业务。真实联调和业务部署必须先完成 T05，替换为经过核验的官方 SDK 并重新打包；不能在正式构建中保留或重新生成占位包。

## 1. 为本项目准备独立目录和 Java

在宝塔新建本项目的测试站点，绑定 `<TEST_DOMAIN>`，网站根目录指定为 `<PROJECT_DIR>/www`。仅在本项目目录中放置文件：

```text
<PROJECT_DIR>/
  app/       后端 JAR
  www/       后台静态文件
  config/    环境变量、启动脚本和日志配置，不在网站根目录内
  logs/      本项目日志
  uploads/   本项目上传文件
  backups/   本项目备份
```

目录归属独立的 `<PROJECT_USER>` / `<PROJECT_GROUP>`，由该账号运行后端。不要覆盖其他站点目录、修改其他站点配置、替换全局 Nginx 配置，或使用按进程名停止所有 Java 程序的命令。

将完整 JDK 21 单独安装到 `<JAVA21_HOME>`，核对官方签名或校验值，然后检查：

```bash
"<JAVA21_HOME>/bin/java" -version
"<JAVA21_HOME>/bin/javac" -version
```

两者都应为 21。不要使用 `update-alternatives`，不要改系统默认 Java、全局 `PATH` 或系统启动配置。后面的启动脚本只为本项目进程设置 `JAVA_HOME`、`PATH`，并使用 Java 的完整路径。

## 2. 准备本项目的数据库和 Redis

在宝塔创建独立 MySQL 8 数据库 `<MYSQL_DATABASE>` 和专属账号 `<MYSQL_APP_USER>`，使用 `utf8mb4`。账号只授权本项目数据库，包含应用读写和 Flyway 迁移需要的建表、改表、索引权限，不授予其他项目数据库权限。首次部署使用空库，不手工导入旧 SQL；应用首次启动会执行 JAR 内的 Flyway V1 基线、V2 补字段和 V3 类型修正迁移。

升级前仅备份本项目库，备份文件放在本项目的非网站目录中。下面命令会交互式询问密码，不把密码写在命令行：

```bash
umask 077
mysqldump \
  --host="<MYSQL_HOST>" --port="<MYSQL_PORT>" \
  --user="<MYSQL_BACKUP_USER>" --password \
  --single-transaction --routines --triggers \
  --databases "<MYSQL_DATABASE>" \
  > "<PROJECT_DIR>/backups/<BACKUP_FILE>.sql"
```

不要备份、清空或恢复整台服务器的全部数据库。已有非空库若没有本项目的 Flyway 历史表，应停止自动迁移，先在副本上核对现有结构及数据，再制定该库的接入方案；不能开启 `baseline-on-migrate` 或手工补一条历史记录来绕过检查。已经执行过的迁移不要修改，后续变更使用新的迁移版本。

Redis 选择该实例中尚未被其他项目使用的 `<REDIS_DB_NUMBER>`，并将编号明确写入下一节的配置。不要沿用默认数据库编号，也不要执行 `FLUSHALL`。如果现有实例只支持数据库 0，例如 Redis Cluster，应为本项目另建独立的测试 Redis 实例，不调整共享实例来迁就本项目。

## 3. 构建并上传程序

在构建机的 `<SOURCE_DIR>` 使用完整 JDK 21。隔离的离线测试且缺少 SDK JAR 时，才运行占位包生成脚本；已经完成 T05 的真实业务构建不运行此命令：

```bash
cd "<SOURCE_DIR>"
JAVA_HOME="<JAVA21_HOME>" PATH="<JAVA21_HOME>/bin:$PATH" \
  bash scripts/build-sdk-stub.sh
```

构建后端和后台页面：

```bash
cd "<SOURCE_DIR>"
JAVA_HOME="<JAVA21_HOME>" PATH="<JAVA21_HOME>/bin:$PATH" ./mvnw -B package
cd ruoyi-ui
npm install
npm run build:prod
```

后台生产构建使用仓库已有的 `VUE_APP_BASE_API=/prod-api`。若构建机的现代 Node 遇到旧版 Webpack 的 `ERR_OSSL_EVP_UNSUPPORTED`，可仅对构建命令设置 `NODE_OPTIONS=--openssl-legacy-provider` 后重跑，不修改服务器全局 Node 配置。

将 `ruoyi-admin/target/ruoyi-admin.jar` 上传到 `<PROJECT_DIR>/app/ruoyi-admin.jar`；将 `ruoyi-ui/dist/` 的内容上传到 `<PROJECT_DIR>/www/`。不要把源码、环境变量文件或配置目录放入网站根目录。

后端打包配置包含 `includeSystemScope`，最终 JAR 的 `BOOT-INF/lib/` 应包含三家联盟 SDK，不需要在服务器上额外拼接开发 classpath。检查 SDK JAR 的清单：T01 占位包标记为 `SDK-Stub: true`、`STUB-NOT-FOR-PRODUCTION`；带这些标记的产物只能用于本节所述的隔离离线测试，不能作为真实业务发布产物。

## 4. 填写项目环境变量

在 `<PROJECT_DIR>/config/app.env` 中按 `.env.example` 填写应用变量。其中 `Local MySQL/Redis containers` 小节仅供本地 Docker Compose；宝塔手工部署使用已创建的专属库和选定的 Redis 数据库，填写下面的应用连接参数即可。该文件仅允许 `<PROJECT_USER>` 读取，权限设为 `600`，不提交 Git、不放入 Nginx 网站根目录。文件会作为 Bash 配置读取，值包含空格或特殊字符时须正确引用。

以下仅为结构示例，值全部需要替换；测试环境也不要复用其他项目的账号、令牌密钥或 Redis 数据库：

```bash
DB_USERNAME='<MYSQL_APP_USER>'
DB_PASSWORD='<MYSQL_APP_PASSWORD>'
TOKEN_SECRET='<PROJECT_TOKEN_SECRET>'
DRUID_USERNAME='<PROJECT_DRUID_USER>'
DRUID_PASSWORD='<PROJECT_DRUID_PASSWORD>'

TBK_APP_KEY='<TBK_APP_KEY>'
TBK_APP_SECRET='<TBK_APP_SECRET>'
TBK_ADZONE_ID='<NUMERIC_TBK_ADZONE_ID>'
PDD_CLIENT_ID='<PDD_CLIENT_ID>'
PDD_CLIENT_SECRET='<PDD_CLIENT_SECRET>'
PDD_POSITION_ID='<PDD_POSITION_ID>'
JD_APP_KEY='<JD_APP_KEY>'
JD_APP_SECRET='<JD_APP_SECRET>'
JD_SITE_ID='<NUMERIC_JD_SITE_ID>'
JD_POSITION_ID='<NUMERIC_JD_POSITION_ID>'
DTK_APP_KEY='<DTK_APP_KEY>'
DTK_APP_SECRET='<DTK_APP_SECRET>'
DTK_ADZONE_ID='<DTK_ADZONE_ID>'
WX_MINIAPP_APPID='<WX_MINIAPP_APPID>'
WX_MINIAPP_SECRET='<WX_MINIAPP_SECRET>'

SPRING_DATASOURCE_DRUID_MASTER_URL='jdbc:mysql://<MYSQL_HOST>:<MYSQL_PORT>/<MYSQL_DATABASE>?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8'
SPRING_DATA_REDIS_HOST='<REDIS_HOST>'
SPRING_DATA_REDIS_PORT='<REDIS_PORT>'
SPRING_DATA_REDIS_DATABASE='<REDIS_DB_NUMBER>'
SPRING_DATA_REDIS_PASSWORD='<REDIS_PASSWORD>'
SERVER_ADDRESS='127.0.0.1'
SERVER_PORT='<BACKEND_PORT>'
RUOYI_PROFILE='<PROJECT_DIR>/uploads'
```

无密码的专用测试 Redis 可将 `SPRING_DATA_REDIS_PASSWORD` 显式留空。纯离线测试时，联盟与微信参数填写明显的开发假值；`TBK_ADZONE_ID`、`JD_SITE_ID` 和 `JD_POSITION_ID` 必须填写数字，例如 `0`。关闭微信登录开关也不会省掉这些参数的占位需求。真实凭据只能由管理员在服务器本地安全填写，不能贴入文档、聊天、构建输出或 PR。

Flyway 随应用启动执行，使用本项目相同数据库地址、账号和密码的独立连接，避免 Druid 将可选权限探测失败误判为连接失效；不需要给账号增加其他数据库的读取权限。保留仓库中的 `spring.flyway.out-of-order=true`，以支持并行开发的迁移按不同顺序合并。不要通过关闭 Flyway 或启用自动 baseline 掩盖迁移错误。迁移失败时先检查对应版本和原始错误，避免反复重启触发同一失败。

## 5. 配置本项目日志并用 java -jar 启动

仓库原有 `logback.xml` 将日志目录固定在另一路径，不能靠 `logging.file.path` 为共享服务器安全地改到项目目录。保留仓库文件，在 `<PROJECT_DIR>/config/logback-test.xml` 放置本项目的外部配置：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="PROJECT_LOG_DIR" value="${project.logs}"/>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <appender name="PROJECT_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${PROJECT_LOG_DIR}/application.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>${PROJECT_LOG_DIR}/application.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>14</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="PROJECT_FILE"/>
    </root>
</configuration>
```

先确认运行账号可以写入 `<PROJECT_DIR>/logs`、`<PROJECT_DIR>/uploads`。创建 `<PROJECT_DIR>/config/start-test.sh`，由宝塔中本项目专属的进程守护任务执行；工作目录设为 `<PROJECT_DIR>/app`，运行账号为 `<PROJECT_USER>`：

```bash
#!/usr/bin/env bash
set -euo pipefail
umask 077
set -a
. '<PROJECT_DIR>/config/app.env'
set +a
export JAVA_HOME='<JAVA21_HOME>'
export PATH="$JAVA_HOME/bin:$PATH"
cd '<PROJECT_DIR>/app'
exec "$JAVA_HOME/bin/java" \
  -Djava.awt.headless=true \
  '-Dproject.logs=<PROJECT_DIR>/logs' \
  '-Dlogback.configurationFile=<PROJECT_DIR>/config/logback-test.xml' \
  '-Dlogging.config=<PROJECT_DIR>/config/logback-test.xml' \
  -jar '<PROJECT_DIR>/app/ruoyi-admin.jar'
```

两个日志系统属性都放在 `-jar` 前：`logback.configurationFile` 覆盖 Logback 早期初始化，`logging.config` 覆盖 Spring Boot 后续初始化。首次在前台运行这个脚本，检查 Flyway V1/V2/V3 成功以及应用启动完成，再交给本项目的进程守护任务。不要使用开发模式代替最终 JAR 的启动验收，也不要让守护程序在迁移失败时无限重启。

## 6. 配置本项目 Nginx 站点

仅编辑宝塔中新建的 `<TEST_DOMAIN>` 站点配置。保留宝塔为本项目生成的证书和验证配置；将下面对应的指令和 `location` 合并进当前站点的 `server` 块，已有的监听端口和 HTTPS 配置继续由宝塔管理。不要复制到全局 Nginx 配置，也不要覆盖其他站点：

```nginx
server {
    listen 80;
    server_name <TEST_DOMAIN>;
    root <PROJECT_DIR>/www;
    index index.html;

    access_log <PROJECT_DIR>/logs/nginx-access.log;
    error_log <PROJECT_DIR>/logs/nginx-error.log;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /prod-api/ {
        proxy_pass http://127.0.0.1:<BACKEND_PORT>/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

`proxy_pass` 末尾的 `/` 会去掉请求前缀：`/prod-api/captchaImage` 转发为后端的 `/captchaImage`。后端必须保持 `SERVER_ADDRESS=127.0.0.1`，不用开放后端端口给外网。HTTPS 证书和访问限制只在本项目测试站点中配置；带占位 SDK 的测试站点应保持隔离访问，不对真实用户开放。

保存前通过宝塔检查本项目站点配置语法，确认通过后按宝塔流程平滑加载，不修改其他站点或全局模板，不重启其他项目的进程。

## 7. 验证及后续更新

按顺序验证：

1. 在本项目库查询 `flyway_schema_history`，确认 V1、V2、V3 成功，后续已合并版本也应成功；迁移成功不代表所有后续业务任务已经完成。
2. 检查监听地址仅为 `127.0.0.1:<BACKEND_PORT>`。
3. 本机请求 `http://127.0.0.1:<BACKEND_PORT>/captchaImage`，确认 HTTP 200、JSON `code=200`；验证码开启时还应有非空的 `uuid` 和 `img`。
4. 通过测试站点请求 `https://<TEST_DOMAIN>/prod-api/captchaImage`，验证同样结果。
5. 打开 `https://<TEST_DOMAIN>/`，确认后台登录页及静态资源加载成功，并检查浏览器请求使用 `/prod-api`。
6. 部署后必须立即登录后台修改 `admin` 初始口令，改为本环境独有的强口令；不得继续使用公开测试种子的口令。V1 将 `sys.account.initPasswordModify` 设为 `1`，首次登录会提示修改，但提示不能代替人工完成修改。修改后退出，用新口令重新登录，并确认旧口令不能登录；不得把新口令贴入文档、聊天或 PR。完成前保持站点隔离，不对外开放。

后续更新先备份本项目库，保留上一版本的 JAR、静态文件和项目配置，再只停止本项目进程、替换本项目文件并重新运行以上检查。新增迁移可能无法随旧 JAR 自动回退；需要恢复时先核对数据库兼容性，只操作本项目的备份，不还原其他项目的库或目录。

本文是人工部署说明，不表示已经访问或部署任何宝塔服务器；本地构建、迁移和启动的实际验证结果以对应 PR 的原始输出为准。
