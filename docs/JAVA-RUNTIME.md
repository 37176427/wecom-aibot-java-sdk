# Java 21 与执行模型

最低 Java 21 是本 SDK 明确的运行时契约。独立 SDK 不意味着只能使用老语言特性；需要控制的是兼容范围、依赖、资源所有权和可观察的线程行为。Java 8/17 宿主不能加载本产物。若未来需要支持较旧 JDK，应另行确认基线并评估 API/二进制兼容，不能仅因为“独立 SDK”就隐式降级。

## 已采用的标准特性

- record：适合 Ack、媒体消息、卡片子结构等值对象；涉及 JsonNode、byte[] 的模型仍使用防御拷贝。record 本身不保证深度不可变。
- CompletableFuture：承载异步成功、错误和组合操作，适合 JDK HttpClient/WebSocket 的异步接口。
- AutoCloseable：连接和下载器可显式释放；不在构造时自动连接，不接管宿主停机钩子。
- JDK HttpClient/WebSocket：无需额外网络框架；支持宿主配置代理、CA、客户端证书和独立所有权。
- 虚拟线程：Java 21 正式特性，可由宿主选择，无需 preview。没有使用 StructuredTaskScope、ScopedValue 等 Java 21 预览 API。

## 完成任务的资源预算

默认完成调度器为最多 2 个 daemon 平台线程、30 秒空闲回收，任务容量默认 2048，可分别通过 completionThreads/maxCompletionTasks 设置。每个已接纳的网络请求在发送前预留一个完成任务名额，直到其 Future 完成及同步 continuation 返回才释放；因此慢 continuation 不会形成无限完成任务积压。预算耗尽的新请求在发报文前返回 QUEUE_FULL/NOT_SENT。

该预算覆盖连接认证结果、发送/上传 ACK 和分片重试延迟。网络发送队列有独立的 maxOutstanding/maxQueuePerRequest 上限，两个限制同时生效。入站监听器仍在独立串行有界队列执行，维持到达顺序；HTTP 下载另有并发、大小和总时限，不使用此 WebSocket 完成预算。

宿主可通过 completionExecutor 注入平台线程池、虚拟线程执行器或业务执行器。SDK 保留一次有界的平台线程调度，避免 direct/caller-runs 执行器将用户 continuation 带入协议锁。外部执行器拒绝任务时，由 SDK 自有调度线程完成已确定的结果，不丢 ACK，也不在协议线程执行用户代码。外部执行器必须执行或明确拒绝已提交任务，不支持静默丢弃策略。

SDK 只关闭自己的执行器。宿主应先 close SDK，再关闭外部执行器，避免已经被外部队列接收的任务被宿主 shutdownNow 丢弃。close 会终止网络和定时器、安排在途 Future 失败；不等待、不强制中断已经执行的用户代码，也不能保证被用户阻塞的 continuation 立即结束。

在默认有限线程池中，请使用 thenCompose 组合异步请求，不要在同步 continuation 内再发 SDK 请求并 join 等待其完成，否则可能耗尽完成线程。耗时 I/O 使用业务 executor 上的 thenComposeAsync/thenAcceptAsync；也可显式选择虚拟线程，容量限制仍然有效。

## 选择虚拟线程的场景

虚拟线程适合大量主要等待 I/O 的任务，不会让 CPU 密集计算、JSON 编解码、AES 运算本身更快。本 SDK 的 WebSocket/HTTP 网络收发已经异步，不需要用虚拟线程包装每一帧。单连接流式发送通常也不值得为每片内容创建额外任务。

在 Java 21 中，虚拟线程持有 synchronized 监视器时执行阻塞操作可能固定住载体线程。SDK 协议锁内不进行 join/get/sleep 等业务等待；自定义 logger 要快速返回，不应在日志适配器内同步请求网络服务。宿主 continuation 的锁和阻塞行为仍由宿主负责。

资料：[Java 21 虚拟线程指南](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)、[Executors API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html)、[HttpClient API](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)。
