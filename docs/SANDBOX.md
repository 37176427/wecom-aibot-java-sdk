# 独立机器人联调

本流程只在新建的测试机器人和专用会话执行，独立于业务系统运行。保持核心 SDK 无 Spring 依赖。SandboxBot 放在 test 源码中，仅编译，不由 Maven 测试自动启动，也不进入发布 jar。

## 创建机器人

1. 企业微信客户端 → 工作台 → 智能机器人 → 创建机器人 → 手动创建 → API 模式创建。
2. 连接方式选择“使用长连接”，名称建议“SDK联调专用”；保存 Bot ID、Secret。
3. 可使用成员只选择参与测试的人员。如无创建入口，让管理员在管理后台“安全与管理 → 管理工具 → 智能机器人”核实创建权限。
4. 添加机器人进行单聊，另建专用测试群并将机器人加入。群聊中 @机器人 后发送测试指令。同一机器人不同时连接其他平台。

资料：[腾讯 WeKnora 项目说明](https://github.com/Tencent/WeKnora/blob/main/docs/IM%E9%9B%86%E6%88%90%E5%BC%80%E5%8F%91%E6%96%87%E6%A1%A3.md)、[腾讯云创建及权限指南](https://cloud.tencent.com.cn/document/product/213/129174)、[腾讯云单聊与群聊说明](https://cloud.tencent.com/document/product/1831/137051)。界面名称可能因客户端版本和企业权限略有差异。

## 本机准备

将仓库 .env.example 复制为 .env.local，填写 WECOM_BOT_ID、WECOM_BOT_SECRET。该文件被 Git 忽略，禁止把真实密钥写入 example、日志或测试报告。不要把 Secret 贴到聊天或放在命令参数中。

可选：WECOM_TEST_USER_ID 指定预先授权的单聊测试人；WECOM_TEST_VOICE_FILE/WECOM_TEST_VIDEO_FILE 指向本地测试语音/视频；WECOM_TEST_CARD_IMAGE_URL 为可公开访问的 HTTPS 测试图片地址，用于 news_notice 卡片。图片和文本文件发送有内置素材，语音、视频与图文卡片需要准备对应测试资源。

```bash
bash scripts/run-sandbox.sh
# 可选择已有的隔离 Maven 缓存：
bash scripts/run-sandbox.sh -Dmaven.repo.local=/path/to/maven-cache
```

脚本只从本地 .env.local/环境变量读取凭证，不将 .env 当 shell 脚本执行。启动时将当前编译产物打包到本次运行专用的临时目录，进程退出后清理；运行中继续编译不会改变该进程加载的类。启动成功后显示 READY 和一次性的 `sdk bind <随机口令>`。

在单聊、群聊分别发送该绑定指令。群聊支持机器人@提及前缀（含名称与指令间的Unicode空格）；这仅属于联调程序的指令解析，SDK保留原始文本。授权粒度为“用户+会话”，未绑定用户或其他群的指令被忽略。最多16个测试绑定，重启进程清除；绑定口令仅提供给测试参与者。主动通知的 chatid 只取已绑定消息的会话，不能在命令里指定任意接收者。

公开测试素材可用 `python3 scripts/prepare-public-test-media.py` 下载到 target/sandbox-media，脚本固定来源提交并校验SHA-256，不读取或修改凭证。音频/视频来自 AndroidX Media 测试资产，来源及许可证保存在目标目录；图文卡片使用 W3C 公开PNG地址。将脚本输出的三项测试配置加入 .env.local 后重启联调入口。`mvn clean` 会清除这些素材，届时需重新准备。

## 手动验收顺序

长连接不支持流式内嵌图片和组合卡片；`sdk image`/`sdk combined`只回复不支持说明，不能作为成功发送这两类消息的验收。

| 阶段 | 测试人员操作 | 预期 |
| --- | --- | --- |
| 认证 | 启动程序 | 状态 READY，凭证不出现在日志 |
| 文本 | sdk text / sdk markdown | 固定测试回复、Markdown展示正常 |
| 流 | sdk stream | 原消息逐步替换，最终结束 |
| 主动通知 | sdk notify | 当前已绑定会话收到测试通知 |
| 被动媒体 | sdk media image/file/voice/video（每次一种） | init/chunk/finish 完成，收到对应媒体 |
| 主动媒体 | sdk push-media image/file/voice/video | 当前测试会话收到对应媒体 |
| 接收媒体 | 发送测试图片、文件、视频 | 下载并解密，日志只输出字节数和 SHA-256 |
| 语音/混排 | 发送语音或图文混排 | 识别消息类型，混排图片下载 |
| 卡片 | sdk card text/news/button/vote/multiple | 对应类型展示；news需图片URL配置 |
| 主动卡片 | sdk push-card text/news/button/vote/multiple | 当前已绑定会话收到独立卡片，交互仍可更新 |
| 卡片交互 | 点击按钮或提交选择 | 收到事件；更新保持原 card_type 和 task_id |
| 反馈 | sdk feedback，然后提交反馈 | FEEDBACK_RECEIVED |
| 欢迎语 | 已预授权用户触发 enter_chat | 欢迎语在窗口内发送 |
| 显式重连 | sdk reconnect，随后再次发送 sdk text | 先提示，再断开/重新认证，绑定会话保持 |
| 关闭 | Ctrl-C | 连接终止，无后台自动重连 |

欢迎语受服务端进入会话触发条件影响。若该用户当天已触发过事件，不能通过伪造 req_id 验收；使用另一个已预授权测试成员或等下一次服务端事件。引用消息可在企微引用测试消息后再发指令，核对原始 quote 解析。

断线重连、认证失败、心跳缺失、迟到ACK、本地背压首先由模拟服务器测试覆盖。真实“被新连接挤下线”测试需要受控启动第二个相同机器人连接，执行前与测试人员约定；不要借用其他正在使用的机器人。

## 记录方式

终端 OK 表示 SDK 操作或 ACK 成功，不表示客户端 UI 已人工验收。测试人员需确认实际显示、媒体可打开、卡片交互和通知目标，并记录在独立联调记录中。FAIL 只输出错误分类、remoteCode、交付状态，不打印原始正文、下载 URL、AES key 或 Secret。日志包含测试会话/用户标识，不应公开上传。

没有收到真实环境结果的项目保持待验；不使用本地模拟结果替代服务端验证。素材分片零基索引尤其要实测确认，因为官方 Node 类型注释与实际实现不一致。
