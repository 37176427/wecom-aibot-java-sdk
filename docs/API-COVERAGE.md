# 官方能力对照与 Java API 边界

对照基线：官方 npm `@wecom/aibot-node-sdk@1.0.7` 的 `dist/client.d.ts`、`types/*.d.ts` 及发布包源代码。固定提交和包摘要见 BASELINE.md。

这里的覆盖指机器人业务操作和协议数据的覆盖，不是 Node 导出符号、EventEmitter API 或内部实现的一对一兼容，不代表每项能力都已完成真实环境验收。

| 官方 WSClient 公开能力 | Java 对应 API | 覆盖说明 |
| --- | --- | --- |
| connect / disconnect / isConnected | connect / disconnect / state / isConnected / close | 认证、重连、停机；Java 的 READY 明确要求认证成功 |
| reply(frame, body, cmd?) | replyRaw + 专用 welcome/update 方法 | 普通回复 JSON 可扩展；不提供任意自定义命令发送器 |
| replyStream | replyStream / StreamSession | Markdown 全量内容、结束标识和反馈；非空图片字段按长连接协议拒绝 |
| replyStreamNonBlocking / hasPendingReplyAck | 同名 Java 方法 / StreamSession.tryUpdate | 非阻塞跳过和有界最终帧；Java 的 pending 查询包含排队项 |
| replyWelcome | replyWelcomeText / replyWelcomeCard | 对应欢迎语类型和 5 秒本地窗口 |
| replyTemplateCard | replyCard / TemplateCard.feedback | 五类模板卡片及反馈 |
| replyStreamWithCard | replyStreamWithCard / streamWithCard | 长连接明确不支持；保留弃用签名并在发送前报 UNSUPPORTED |
| updateTemplateCard | updateCard | task_id 校验、userids、5 秒本地窗口 |
| sendMessage | sendMarkdown / sendCard / sendMedia | 全部基线主动类型；通道限制通过方法区分 |
| uploadMedia | uploadMedia | 四类素材、MD5、三步上传、零基索引、并发/重试 |
| replyMedia / sendMediaMessage | replyMedia / sendMedia | 图片/文件/语音/视频，以及视频标题和描述 |
| downloadFile / api.downloadFileRaw | downloader().download(uri, aesKey) | aesKey=null 为原始下载；非空为解密下载；保留文件名 |

## 数据和事件

- 对官方SDK列出的六类入站消息提供模型，text/image/mixed/voice/file引用通过IncomingMessage.Content访问；原始body、headers和未知字段保留。原生VIDEO回调尚未在本次环境观察到：转发机器人视频被平台拒绝，直接上传MP4得到FILE回调，不能笼统表述为所有视频输入路径均支持。
- enter_chat、template_card_event、feedback_event、disconnected_event 由 IncomingEvent.Type 区分；新增事件进入 UNKNOWN，data 保留完整内容。details 提供事件专属对象；template_card_event 的 task_id/event_key 支持 event.template_card_event 嵌套结构及扁平结构。
- 所有官方 TemplateCard 顶层字段都有 builder 入口：source、action_menu、main_title、emphasis_content、quote_area、sub_title_text、horizontal_content_list、jump_list、card_action、card_image、image_text_area、vertical_content_list、button_selection、button_list、checkbox、select_list、submit_button、task_id、feedback。
- 模板卡片类型条件、完整必填/互斥字段组合，以及最终媒体格式和权限由服务端校验；本地测试覆盖报文结构，不模拟全部企微业务校验规则。
- WecomCrypto 对应官方导出的签名/消息加解密、AES key 解码、PKCS#7 工具；decryptFile 对应媒体解密。

## 协议限制与 API 差异

- 长连接不支持 stream.msg_item 和 stream_with_template_card；Java按协议拒绝它们，包括 replyRaw 的已知不支持格式，不跟随 Node 的对应示例发送无效内容。StreamSession保守执行10分钟窗口；低阶流调用由宿主自行管理时间。

- 不公开 Node 的 WsConnectionManager、MessageHandler、EventEmitter 原型、随机字符串工具或任意命令底层发送接口。Java 使用 BotListener、受连接代次约束的 ReplyContext 和 UUID 标识；这些差异不减少固定基线机器人业务通道。
- Java 拒绝非法 JSON，Node 1.0.7 先清理控制字符；这属于行为差异，不承诺宽松解析兼容。
- Java 的 ACK 超时会封锁同 req_id 的后续发送，避免迟到 ACK 错配；Node 实现继续处理后续队列。不会自动重放结果不确定的消息。
- 客户端 TLS/代理由可注入的 JDK HttpClient 配置，不照搬 Node wsOptions。
- SDK 不保证传播调用线程的 ThreadLocal/MDC/事务上下文。执行器从 SDK 调度线程接收任务，仅注入执行器不能自动捕获原始调用线程上下文；宿主应显式关联并恢复所需上下文。回调顺序、容量、关闭语义独立于是否使用虚拟线程。

## 1.0.0 真实验收边界

以下能力已有实现和本地测试，但尚未完成真实机器人验收，1.0.0 不承诺它们已通过端到端验证：

| 能力 | 实现入口 | 真实验收状态 |
| --- | --- | --- |
| 欢迎语 | replyWelcomeText / replyWelcomeCard、enter_chat | 待真实 enter_chat 事件触发并确认展示与回复窗口 |
| 引用消息 | IncomingMessage.Content 中的引用内容 | 待真实引用消息回调及内容解析确认 |
| 图文混排 | IncomingMessage 的 MIXED 类型及混排内容 | 待真实混排回调、文本和图片下载验证 |
| 原生视频回调 | IncomingMessage 的 VIDEO 类型及媒体下载 | 尚未观察到 VIDEO 回调；直接上传 MP4 得到 FILE 回调并完成解密，不能替代原生 VIDEO 验收；转发机器人视频被平台拒绝且无回调 |

本地回归的范围和执行方法见 [测试说明](VERIFICATION.md)。真实认证、群聊、媒体展示与卡片交互应在接入方的专用测试环境完成验收。

## 发布与生产接入

1.0.0 随版本公开上述待验能力和长连接协议限制，不将实现覆盖等同于全场景生产验证。发布检查包括完整构建、示例编译、许可证、制品内容和凭证排除。

生产接入应按实际使用能力补齐待验项，并在目标网络、连接数和流量下验证限频、持续运行、内存与延迟；不能用本地测试替代这些环境验证。
