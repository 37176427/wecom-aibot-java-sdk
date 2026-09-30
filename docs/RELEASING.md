# 构建与发布

项目使用 Java 21 和 Maven。公开源码位于 https://github.com/37176427/wecom-aibot-java-sdk 。

```bash
mvn clean verify
mvn install
```

`verify` 运行本地测试并生成普通 jar、sources jar 和 javadoc jar；`install` 额外安装到本机 Maven 仓库。测试无需机器人凭据，不会自动连接真实机器人。

Maven 坐标为 `io.github.user37176427:wecom-aibot-java-sdk:1.0.0`，Java 包名前缀为 `io.github.user37176427.wecom.aibot`。GitHub 用户名以数字开头，因此 Java 命名空间使用 `user37176427`。当前未发布到 Maven Central，不应仅凭依赖片段假定远端仓库已有该版本。

发布前运行完整测试，核对 JAR 中的许可证与第三方声明。仅提交源码、构建定义、测试、文档和必要的文本测试向量；不提交 target、JAR、class、IDE 文件、日志、真实凭据或媒体产物。版本按 SemVer 管理，不覆盖已发布版本。

更新官方协议基线时，在 BASELINE.md 记录固定版本和提交，补充相应回归测试，再使用独立测试机器人验证需要服务端配合的行为。
