# 配置契约

所有配置由 ClientOptions.builder 提供；SDK 不读取 Spring、Nacos、环境变量或业务数据库。环境变量/本地文件加载只存在于测试入口，由宿主自行选择配置来源。配置对象构建后不可变；变更连接凭证或运行策略时，宿主关闭旧实例并使用新配置创建实例。

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| botId / secret | 必填 | 独立机器人凭证，不进入 SDK 日志 |
| endpoint | wss://openws.work.weixin.qq.com | WS/WSS 地址；私有部署用管理员提供的地址 |
| httpClient | SDK 创建 | 宿主可提供代理、CA、客户端证书；外部实例不由 SDK 关闭 |
| scene / pluginVersion | 不发送 | 可选认证扩展参数 |
| connectTimeout | 10秒 | WebSocket 握手超时；外部 HttpClient 的 HTTP 建连策略由宿主设置 |
| ackTimeout | 5秒 | 认证/业务 ACK 等待上限 |
| queueTimeout | 30秒 | 尚未发出的队列项有效期 |
| heartbeatInterval | 30秒 | 心跳间隔 |
| maxMissedHeartbeats | 2 | 未 ACK 心跳数达到阈值后，下次检查断线 |
| reconnectDelay / reconnectMaxDelay | 1秒 / 30秒 | 指数重连退避基础和上限；上限不得小于基础 |
| maxReconnectAttempts / maxAuthRetries | 10 / 5 | 网络/认证独立重试预算；0 禁止，-1 无限 |
| maxQueuePerRequest / maxOutstanding | 500 / 2000 | 单 req_id / 全局在途与排队项上限 |
| maxCallbackQueueSize | 2000 | 入站监听器队列容量，与出站容量独立 |
| completionThreads / maxCompletionTasks | 2 / 2048 | SDK 完成线程数及从接纳到 continuation 返回的任务预算 |
| completionExecutor | SDK 平台线程池 | 可注入宿主执行器，包括虚拟线程；资源归宿主 |
| maxFrameChars | 2×1024×1024 | 单个入站 JSON 消息的字符数上限 |
| maxUploads | 2 | 同时上传的整个素材任务数 |
| uploadMaxConcurrency | 4 | 分片并发附加上限，取 1..4；实际值还受官方 4/3/2 自适应策略限制 |
| uploadChunkRetries | 2 | 单片失败后的额外尝试次数；0 禁止，不支持无限重试 |
| uploadRetryDelay | 500毫秒 | 单片重试线性退避基础，依次为基础×1、基础×2… |
| downloadTimeout | 10秒 | HTTP 下载总时限（不含之后的本地解密计算） |
| maxDownloads / maxDownloadBytes | 4 / 50MiB | 下载并发和响应体上限 |
| logger | JDK System.Logger | 生命周期/错误类别；适配器须快速返回 |

Duration 配置至少 1 毫秒，且必须能表示为 long 纳秒值；容量必须为正整数。排队期限使用单调时钟的相对差值计算，保持协议窗口上限并避免绝对时间相加回绕造成误过期。ACK、队列、重试、完成预算相互独立，不能通过提高某一项绕过另一项。调整 uploadChunkRetries 只影响索引明确的素材分片，不会使普通消息、upload init/finish 自动重试。

以下是协议约束而非自由运行参数：AES-256-CBC 的 key/IV/填充规则、512KiB 分片最大值、100片传输上限、流式内容20480字节、欢迎语/卡片更新5秒窗口、流式10分钟完成窗口。具体媒体类型大小、格式和频率还由企微服务端裁定。

长连接不支持流式msg_item和stream_with_template_card；这两项不是可通过配置开启的能力，SDK发送前返回UNSUPPORTED。
