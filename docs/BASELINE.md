# 官方基线与能力矩阵

核验日期：2026-09-22。Java SDK 版本独立于官方 Node/Python 版本。

| 来源 | 当前版本 / 固定标识 |
| --- | --- |
| 官方 npm `@wecom/aibot-node-sdk` | 1.0.7，gitHead `ea48edf7c99be0609fe9740050f4942f897a5d95` |
| npm tarball SHA-1 | `be98b177d5b7ff7af16edb8be2c1c3783d605f59` |
| 官方 GitHub Node main | package.json 1.0.6，commit `80615b987ef69c6028ad764924609247c0725955`，无 tag |
| 官方 GitHub Python main | pyproject.toml 1.0.1，commit `6bcb59a9a636c566f4c6ea5268b228e3def1611a`，无 tag |
| 核验时 PyPI 发布 | 1.0.2（辅助交叉核验，能力基线以 Node 为准） |

来源：[Node 官方仓库](https://github.com/WecomTeam/aibot-node-sdk)、[npm 发布元数据](https://registry.npmjs.org/@wecom/aibot-node-sdk/1.0.7)、[Python 官方仓库](https://github.com/WecomTeam/wecom-aibot-python-sdk)、[长连接协议](https://developer.work.weixin.qq.com/document/path/101463)。报文依据固定官方发布包中的源代码、类型和示例核验。不能据此宣称已完成真实服务端兼容认证。

## 能力和通道边界

| 能力 | Java API / 报文 | 边界 |
| --- | --- | --- |
| Bot ID/Secret 认证 | connect / aibot_subscribe | 可带 scene、plug_version；连接成功与认证成功分开 |
| 心跳、重连、停机 | ping / disconnect / close | 网络和认证重试独立计数；被挤下线不自动重连 |
| ACK 与回复队列 | CompletableFuture<Ack> | 同 req_id 串行、不同 req_id 并发；全局和单请求容量限制 |
| 接收文本、图片、混排、语音、文件、视频 | IncomingMessage + Content | 语音为识别文本；引用与原始未知字段保留；混排、引用及原生 VIDEO 真实回调待验 |
| 普通文本/Markdown 回复 | replyText / replyMarkdown / replyStream | 普通文本与 Markdown 均编码为 stream，完整内容覆盖前一帧 |
| 流式内嵌图片 | UNSUPPORTED / NOT_SENT | 长连接文档明确不支持 msg_item，发送前拒绝 |
| 被动媒体回复 | replyMedia | image/file/voice/video，使用上传取得的 media_id |
| 主动通知 | sendMarkdown / sendCard / sendMedia | 不提供主动 stream、mixed、纯 text 通道 |
| 模板卡片 | TemplateCard | text_notice/news_notice/button_interaction/vote_interaction/multiple_interaction；所有官方子字段可建模 |
| 流式组合卡片 | UNSUPPORTED / NOT_SENT | 长连接文档明确不支持组合消息，发送前拒绝；独立卡片不受影响 |
| 欢迎语 | replyWelcomeText / replyWelcomeCard | enter_chat 的 req_id，5 秒内；仅 text/template_card；真实验收待完成 |
| 卡片更新 | updateCard | template_card_event 的 req_id，5 秒内；task_id 一致；可选 userids |
| 事件 | IncomingEvent | enter_chat/template_card_event/feedback_event/disconnected_event；未知事件保留 |
| 上传 | uploadMedia | init → chunk → finish；512 KiB/片、最多 100 片，按总分片数控制并发；分片可重试 2 次 |
| 下载 | MediaDownloader | HTTP GET，大小和时间上限；AES-256-CBC，IV=key 前16字节，PKCS#7 32 字节填充 |
| 通用加解密 | WecomCrypto | 官方导出的签名、消息加解密辅助函数；不提供 Webhook 服务 |
| 扩展 | rawFrame / body / replyRaw | 原始 JSON 保留；显式高级入口不保证未知能力受服务端支持 |

### 协议差异处理

- 分片索引按 Node 1.0.7 实际实现从 **0** 开始；其类型注释写 1，与发送代码不一致。真实联调已通过 0、1 两片 MP4 上传及播放验证。
- 对 ACK 超时不自动重放。由于同一 req_id 没有序号，超时后封锁该回复上下文，队列余项失败，迟到 ACK 不会完成下一项。
- 输入 JSON 严格解析，不像 Node 1.0.7 删除控制字符。非法帧报协议错误并保持连接；不会静默改变用户内容。
- 本地超时、取消、断线只能说明调用状态，无法撤销已到达服务端的消息。异常包含 NOT_SENT / UNKNOWN / REJECTED 交付状态。
- 频率、媒体格式/类型大小限制、会话权限、欢迎语窗口、卡片最终合法性由企微服务端裁定。50 MiB 只是传输分片上限，不表示每类媒体都允许该大小。

2026-09-22 用户提供了长连接文档相关原文，确认不支持 msg_item、不支持流式与卡片组合，且流式须10分钟内结束。以上限制优先于 Node SDK 中存在的对应方法和样例。官方仓库 [Issue #26](https://github.com/WecomTeam/aibot-node-sdk/issues/26) 及本次真实展示结果也与 msg_item 限制一致。StreamSession 会按10分钟窗口保守限制刷新/结束。

1.0.0 的欢迎语、引用、混排和原生 VIDEO 回调尚未完成真实验收，具体边界见 [API 能力对照](API-COVERAGE.md#100-真实验收边界)。
