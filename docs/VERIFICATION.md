# 测试说明

要求 Java 21 和 Maven：

```bash
mvn clean verify
mvn dependency:tree
```

测试使用本地 WebSocket/HTTP 服务、可控迟到回调和固定测试向量，不需要 .env.local 或真实机器人凭据。示例和 SandboxBot 随 testCompile 编译，不会自动运行。测试报告位于 target/surefire-reports，构建目录不纳入 Git。

覆盖认证、心跳、重连、连接代次隔离、ACK 顺序与不确定交付、队列及执行器边界、流式结束、媒体上传/下载、模板卡片、事件解析、资源回收及官方密码学测试向量。向量中的密钥为合成测试数据，生成逻辑位于 scripts/generate-official-fixtures.mjs，不是部署凭据。

本地模拟服务不能替代真实平台验收。欢迎语、引用、图文混排、原生视频回调、网络代理及长期负载等场景仍应由接入方在专用测试环境验证。真实联调步骤见 [SANDBOX.md](SANDBOX.md)，协议基线见 [BASELINE.md](BASELINE.md)。
