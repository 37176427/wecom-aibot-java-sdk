# wecom-aibot-java-sdk

Powered by GPT-6 Astra

企业微信智能机器人长连接 Java SDK（独立实现，非企业微信官方 Java SDK）。最低 **Java 21**，Maven 单 jar，运行时只依赖 Jackson 2。覆盖认证、连接生命周期、六类接收消息、流式文本、四类媒体、五类模板卡片、主动通知、上传与下载解密。

当前版本 `1.0.0`，对齐官方 npm `@wecom/aibot-node-sdk@1.0.7`。完整来源、固定提交和通道限制见 [能力矩阵](docs/BASELINE.md)，方法级覆盖和差异见 [API 对照](docs/API-COVERAGE.md)。本地协议测试可复现，验证方法见 [测试说明](docs/VERIFICATION.md)；本地验证不等同于企微环境验收。

**1.0.0 验收边界：** 欢迎语、引用、图文混排和原生 VIDEO 回调已有实现与本地验证，但尚未完成真实机器人验收，详见 [能力对照](docs/API-COVERAGE.md)。

## 构建与依赖

```bash
mvn verify
# 需要给本机其他工程使用时自行安装：
mvn install
```

```xml
<dependency>
  <groupId>io.github.moment</groupId>
  <artifactId>wecom-aibot-java-sdk</artifactId>
  <version>1.0.0</version>
</dependency>
```

当前通过 GitHub 提供源码；上述依赖需先运行 `mvn install` 安装到本机，不表示已发布到 Maven Central。Jackson 默认 `2.21.4`。宿主可通过 BOM 管理同一 Jackson 2 系列版本；升级或降级后运行 SDK 测试。没有 Spring、Redis、数据库、Nacos、业务租约或 AI 输出转换依赖。

## 快速开始

```java
import io.github.moment.wecom.aibot.*;

var client = new WecomAiBotClient(
    ClientOptions.builder(System.getenv("WECOM_BOT_ID"),
                          System.getenv("WECOM_BOT_SECRET")).build());
client.addListener(new BotListener() {
    @Override public void onMessage(IncomingMessage message) {
        if (message.type() != IncomingMessage.Type.TEXT) return;
        var stream = client.stream(message.context());
        stream.update("正在处理…")
              .thenCompose(ack -> stream.finish("**完成**：" + message.content().text()))
              .exceptionally(error -> { /* 宿主记录错误，避免盲目重试 */ return null; });
    }
    @Override public void onEvent(IncomingEvent event) {
        if (event.type() == IncomingEvent.Type.ENTER_CHAT)
            client.replyWelcomeText(event.context(), "你好，我是智能助手。");
    }
});
client.connect().join(); // WebSocket 已建立且认证成功
// 由宿主已有定时任务触发；SDK 自身不调度业务任务。
client.sendMarkdown("userid_or_chatid", "**定时通知**").join();
// 宿主退出或失去租约时调用：
client.close();
```

实际服务应长期保存 client，在关闭钩子释放，不要在每次消息处理时创建连接。完整可运行入口 [BasicBot.java](src/test/java/io/github/moment/wecom/aibot/examples/BasicBot.java) 使用环境变量；Maven 会编译示例，但不会执行或发送真实消息。

## 生命周期与并发契约

- `connect()`：异步认证；重复调用共享当前认证结果。在自动重连期间调用会等待新认证。`isConnected()` 仅在 READY 返回 true；`state()` 区分建连、认证、重连、失败、关闭。
- `disconnect()`：取消连接、重连、心跳和排队请求；以后只有显式 `connect()` 才可重新启动。`close()`：永久终止，同一实例不能复用。被 `disconnected_event` 挤下线时不自动争抢连接。
- 默认网络重连最多 10 次、认证失败重试最多 5 次，独立计数，认证成功重置。指数退避默认从 1 秒到 30 秒，可配置基础和上限；`-1` 表示无限重试，`0` 禁止重试。心跳默认 30 秒，连续两次无 ACK 后在下次心跳检查断线。
- 每次回调的 `ReplyContext` 绑定 client 和连接代次。断线后旧上下文失效，迟到回调不能影响新连接。宿主租约逻辑应 `close()` 旧 client，获得新租约后另建实例。
- 同 `req_id` 逐条等待 ACK，不同请求并发等待。每请求默认最多 500 项、全局 2000 项（均含已发待 ACK）；超出立即 `QUEUE_FULL`，不阻塞调用线程。ACK 默认 5 秒；未发队列项默认 30 秒过期。
- 回调在独立单线程有界队列执行，按到达顺序通知监听器；监听器异常被隔离。请快速返回并异步处理。回调队列满时记录日志并断开重连，已丢弃回调没有交付保证，SDK 不提供持久化消费。WebSocket Future 完成默认在独立有界平台线程池执行（最多 2 个线程、2048 个已接纳任务），可配置或由宿主提供虚拟线程执行器；不会在协议锁内执行业务 continuation。任务容量覆盖在途请求到同步 continuation 返回的整个过程；达到上限时，新请求返回 QUEUE_FULL/NOT_SENT。
- 取消返回的发送 Future 只放弃等待，不能撤销发送，不会跳过队列顺序。关闭会使在途请求返回 UNKNOWN，未发请求返回 NOT_SENT；不承诺服务端撤回。
- `HttpClient` 可注入以配置代理、自定义 CA、双向 TLS，宿主拥有该实例的生命周期。SDK 只关闭自己创建的 HttpClient。默认不绕过 TLS 验证。

```java
var options = ClientOptions.builder(botId, secret)
    .ackTimeout(java.time.Duration.ofSeconds(5))
    .queueTimeout(java.time.Duration.ofSeconds(30))
    .maxQueuePerRequest(100).maxOutstanding(1000)
    .maxReconnectAttempts(10).maxAuthRetries(5)
    .logger((level, message) -> System.getLogger("bot").log(level, message))
    .build();
```

线程配置：`completionThreads`、`maxCompletionTasks`、`completionExecutor`。外部执行器由宿主关闭；必须执行或明确拒绝任务，不能静默丢弃。同步 continuation 应快速返回，嵌套请求用 `thenCompose`，不要在完成线程里阻塞 `join()` 等待其他 SDK 请求。详细边界见 [Java 运行时说明](docs/JAVA-RUNTIME.md)。

新增运行配置：`maxUploads`、`maxCallbackQueueSize`、`reconnectMaxDelay`、`uploadChunkRetries`、`uploadRetryDelay`、`uploadMaxConcurrency`。

其他选项：`endpoint`、`httpClient`、`scene`、`pluginVersion`、`connectTimeout`、`heartbeatInterval`、`reconnectDelay`、`maxMissedHeartbeats`、`maxFrameChars`、`downloadTimeout`、`maxDownloads`、`maxDownloadBytes`。自定义 logger 应快速返回；SDK 日志不包含 secret、消息正文、媒体字节、下载 URL 或原始服务端错误文本。

宿主需要虚拟线程时可显式启用，SDK 不要求所有宿主采用同一种线程策略：

```java
try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
     var client = new WecomAiBotClient(ClientOptions.builder(botId, secret)
         .completionExecutor(executor).maxCompletionTasks(2048).build())) {
    client.connect().join();
    client.sendMarkdown(chatId, "通知").join();
} // 先关闭 SDK，再关闭宿主执行器
```

## 消息模式

**协议边界：** 长连接明确不支持 `stream.msg_item` 和 `stream_with_template_card`。SDK 对这些请求返回 `UNSUPPORTED / NOT_SENT`，包括原始回复入口；不会自动拆成多条消息。图片使用独立媒体消息，卡片单独发送。

| 场景 | API | 服务端消息类型 |
| --- | --- | --- |
| 普通文本/Markdown 回复 | `replyText` / `replyMarkdown` | `stream` + `finish=true` |
| 流式回复 | `stream(context)` / `replyStream` | `stream`，每帧是完整当前内容 |
| 流式内嵌图片 | 兼容参数非空时明确拒绝 | 长连接不支持 `msg_item` |
| 模板卡片回复 | `replyCard` | `template_card` |
| 流与卡片组合 | 旧方法已弃用，调用明确拒绝 | 长连接不支持 `stream_with_template_card` |
| 媒体回复 | `replyMedia` | image/file/voice/video |
| 主动通知 | `sendMarkdown` / `sendCard` / `sendMedia` | markdown/template_card/四类媒体 |
| 欢迎语 | `replyWelcomeText` / `replyWelcomeCard` | 专用欢迎语命令，text/template_card |
| 卡片更新 | `updateCard` | 专用更新命令，update_template_card |

主动发送不支持 stream、mixed 或纯 text API；需要文本通知时使用 Markdown。入站语音是转写文本，不是下载音频链接。接收的图片、文件和视频使用消息自身的 aeskey。

`StreamSession` 约束首帧反馈、结束状态和 **10分钟** 完成窗口；已接纳请求的发送失败会终止该 session；若因容量不足在接纳前返回 `QUEUE_FULL / NOT_SENT`，不会消耗首帧反馈或结束状态，调用者可在容量恢复后重试。窗口从首次接纳开始保守计时，排队帧也不得超过该截止时间。低阶 `replyStream` 不维护流状态，调用者需遵守相同的服务端时间窗口。`replyStreamNonBlocking` 返回空 Optional 表示中间帧被跳过；最终帧走正常有界队列，不能突破容量或网络错误。

```java
var stream = client.stream(message.context());
stream.send("正在处理", false, "stream-feedback")
      .thenCompose(ack -> stream.finish("处理完成"));
```

模板卡片使用 `replyCard` 或 `sendCard` 单独发送。为了让旧调用明确失败，组合消息方法保留弃用签名；非空 InlineImage 兼容参数也会被长连接发送入口拒绝，不代表服务端支持。

欢迎语必须使用 `enter_chat` 上下文，卡片更新必须使用 `template_card_event` 上下文且 task_id 一致。SDK 在实际写入前检查本地接收后的 5 秒窗口；网络时延可能导致服务端仍判超时。支持 `userids` 指定卡片更新对象。`IncomingEvent.details()` 返回按 eventtype 包裹的事件专属内容，`data()` 保留完整 event 对象；按钮的 `eventKey()`、`taskId()` 同时兼容嵌套结构与扁平结构。

## 媒体上传与下载

```java
client.uploadMedia(fileBytes, MediaType.FILE, "report.pdf")
      .thenCompose(media -> client.replyMedia(message.context(),
          new MediaMessage(MediaType.FILE, media.mediaId())));

client.uploadMedia(videoBytes, MediaType.VIDEO, "demo.mp4")
      .thenCompose(media -> client.sendMedia(chatId,
          new MediaMessage(MediaType.VIDEO, media.mediaId(), "演示", "视频说明")));

var content = message.content();
client.downloader().download(java.net.URI.create(content.url()), content.aesKey())
      .thenAccept(file -> { byte[] plaintext = file.bytes(); /* 交给宿主存储 */ });
```

上传默认最多两个任务同时执行（`maxUploads` 可配置），每任务 512 KiB/片、最多 100 片（50 MiB 为分片总上限，具体媒体格式/大小仍以服务端为准）。1–4 片全并发，5–10 片并发 3，更多并发 2，可通过 `uploadMaxConcurrency` 进一步降低；索引从 0 开始，MD5 按原始字节计算。只有同一上传会话中的同一分片会在明确 ACK 错误或 ACK 超时后重试，默认最多 2 次（可配置次数和间隔）；init、finish 和消息发送不重试。断线终止上传，不在新连接续传。media_id 基线有效期三天。

下载默认并发 4、10 秒总时限、50 MiB 响应上限，可调。超时/close 会取消底层 HTTP 请求及迟到的响应体订阅；取消返回的 Future 仅放弃等待，容量仍在请求终结后回收。无 aesKey 时返回原始文件；有 key 时使用 AES-256-CBC、key 前 16 字节 IV 和 32 字节 PKCS#7 填充校验。文件名仅是未信任的响应元数据；SDK 不写文件，也不将其直接拼成路径。下载 URL 应来自已验证的企微回调，不要开放成任意用户 URL 代理。

五类卡片和媒体的可编译示例见 [MediaAndCards.java](src/test/java/io/github/moment/wecom/aibot/examples/MediaAndCards.java)。卡片 builder 覆盖官方全部子字段；类型特有必填项和最终合法性由企微服务端校验。

## 错误与扩展

异步请求失败以 `BotException` 为 cause：`code()` 是 SDK 分类，`remoteCode()` 是企微错误码，`response()` 保留完整错误 ACK（包括 errmsg）。`delivery()` 区分：

- `NOT_SENT`：未交给 WebSocket 发送，例如未认证、过期上下文、队列满、队列等待超时。
- `REJECTED`：服务端明确拒绝，按错误码处理。
- `UNKNOWN`：已交给网络但未取得确定结果，例如 ACK 超时、断线。不要无条件重试，可能重复发送。

同一 req_id 的 ACK 没有帧序号，因此一条回复超时后，该上下文和后续队列项都失败；迟到 ACK 不会完成下一条。SDK 不提供跨进程去重、消息回放或恰好一次语义。

`IncomingMessage`、`IncomingEvent` 保留完整 `WsFrame`、原始 body、headers 和未知字段，未知类型为 UNKNOWN；消息引用通过 `quote()` 访问。`TemplateCard.extra`/`fromJson` 和 `replyRaw` 是显式扩展入口，不承诺服务端支持任意字段或命令。非法 JSON 不做文本替换，报 PROTOCOL 后继续接收；入站帧默认 2 Mi 字符限制。`WecomCrypto` 另提供官方基线导出的通用签名/消息加解密，不包含 Webhook 服务。

## 验证和发布

创建独立测试机器人的步骤及指令见 [联调指南](docs/SANDBOX.md)，全部配置与默认值见 [配置契约](docs/CONFIGURATION.md)。

参见 [设计及测试计划](docs/DESIGN.md)、[验证报告](docs/VERIFICATION.md)、[发布与升级](docs/RELEASING.md)。测试使用本地真实 WebSocket/HTTP 服务和可控迟到回调，不需要生产凭证。MIT 许可；官方适用版权和许可随 jar 的 `META-INF` 一起分发。
