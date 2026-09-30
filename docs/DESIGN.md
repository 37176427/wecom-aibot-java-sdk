# 接口边界与测试计划

使用 Java 21 + JDK HttpClient/WebSocket，单 Maven jar，坐标 io.github.user37176427:wecom-aibot-java-sdk。运行时只依赖 Jackson 2，使用 JDK 日志适配接口，不依赖 Spring 或业务系统。

## 设计

- WecomAiBotClient 负责生命周期、认证、ACK、队列；连接代次隔离迟到 open/text/close/error 回调。disconnect 可显式 connect，close 永久终止。宿主失去租约应 close 旧实例。
- WebSocket 完成任务使用有界平台线程池（默认最多2线程、2048任务）；发送前预留完成容量，直到同步 continuation 返回后释放。宿主可注入执行器（包括虚拟线程），由宿主拥有其生命周期；外部拒绝时由 SDK 完成调度器承接。
- 网络状态用单一同步锁保护；网络异步发送全局串行，避免 JDK WebSocket 并发发送 IllegalStateException；ACK 等待按 req_id 独立。用户回调及 Future 完成派发到独立执行器，不能阻塞心跳锁。
- ReplyContext 随回调绑定当前连接代次；断线后旧上下文不可用于新连接，避免过期消息跨连接发送。队列限制同时覆盖待 ACK 和待发。
- 入站消息/事件提供类型访问器，完整 JSON 防御拷贝。出站消息采用类型区分通道；TemplateCard builder 及子结构覆盖复杂卡片，extra 字段用于未来扩展。
- StreamSession 是可选的有状态便利接口，约束首帧反馈、结束状态和10分钟窗口。低阶 replyStream 支持宿主自行管理流生命周期。长连接不支持内嵌图片和组合卡片，所有发送入口明确拒绝这两种已知不支持格式。
- SDK 不包含分布式租约、消息路由、业务去重、AI 输出转换或定时任务。重连只恢复连接，不重放消息。上传绑定连接代次；仅同 upload_id/chunk_index 的分片重试，init/finish 不盲重试。
- HTTP 和 WebSocket 可注入宿主 HttpClient（代理、SSLContext、客户端证书）；不提供跳过证书验证选项。只关闭 SDK 自有网络资源。

## 测试覆盖

1. 本地真实 WebSocket 握手与 JSON 帧：认证成功/失败/超时、认证重试耗尽、网络重试耗尽、心跳超时、服务端挤下线。
2. 生命周期：显式 disconnect/close 取消重连，迟到 open/ACK/close 无法重启实例，旧上下文拒绝。
3. 发送：不同请求并发、同 req_id 串行、全局/局部背压、ACK 错误/超时、队列过期、迟到 ACK 隔离；发送不确定不重放。
4. 消息：六类入站、引用、四类事件、未知事件/字段、监听器异常隔离、非法 JSON；各通道真实报文结构。
5. 流：完整内容替换和反馈、非阻塞中间帧跳过、结束后拒绝、10分钟窗口以及不支持模式发送前拒绝。
6. 媒体：512 KiB 边界、多片重组/MD5、并发上限、同片重试、init/finish 不重试、断线停止；本地 HTTP 下载、文件名、大小/超时、AES 填充错误。
7. 卡片：五类卡片报文、欢迎语和更新的事件/task_id/5秒约束。
8. 官方密码学固定向量与 Java 互操作；Maven test/verify、Javadoc/source jar、依赖树。

实际接入时，认证、真实收发/媒体格式限制、分片零基索引、流/卡片界面表现、反馈事件及企微频率/时间窗口需要独立环境验收。不向生产会话发测试消息。
