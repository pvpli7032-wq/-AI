# 联盟 SDK 占位源码

这些类只覆盖当前代码调用到的拼多多 SDK 类型，用于本地编译、测试和开发启动，不可用于上线。客户端构造不发请求；`execute` / `syncInvoke` 一律抛出包含“占位 SDK”的异常，不返回模拟成功数据。大淘客代码不受影响。

使用完整 JDK 21，在仓库根目录执行：

```bash
bash scripts/build-sdk-stub.sh
./mvnw -B -DskipTests compile
./mvnw -B test
```

脚本生成 `ruoyi-union-sdk/pom.xml` 指定的五个 JAR（淘宝两个为官方 SDK，已提交，脚本只生成拼多多的占位），输出已由 `.gitignore` 排除。已存在的文件（包括官方 SDK）不会被覆盖。JAR 的 manifest 带有 `SDK-Stub: true` 标记；源码和脚本提交，生成的 JAR 不提交。

真实接口联调须先完成 T05，按其要求替换为官方 SDK。T01 不包含数据库迁移和发布打包配置，这些由线 3 的 T02 完成。
