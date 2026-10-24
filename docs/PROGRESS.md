# 开发进度（PROGRESS）

> 每个阶段结束更新：完成了什么、怎么验证、已知问题、协议变更原因。
> 最新阶段在最上方。

---

## 阶段 4：端到端测试、压测与优化 ✅

**日期**：2026-10-25 · **范围**：`im-bench`（E2E 测试 + 压测工具）、`im-server`（两项写库优化）、`docs/benchmark.md`

### 完成内容

| 项 | 说明 |
|---|---|
| 端到端测试 | `im-bench` 内 `E2eTest`：真实服务端（随机端口）跑通 注册→加好友→私聊→对方离线→上线收离线→群聊→文件上传下载 MD5→强制断开重连→同 msgId 不重复入库 |
| 压测工具 | `im-bench` 四场景：`connections` / `throughput` / `fanout` / `stability`；参数 `--scenario --connections --pairs --rate --duration --members --messages --size`；复用 `im-client` net 层，所有模拟客户端**共享一个心跳调度线程**（`ImConnection` 新增共享调度器构造） |
| 优化 1 | 消息本体 + 全部收件箱记录**单事务 + JDBC batch** 写入；幂等改由 `msg_id` 唯一索引兜底，去掉每消息的 `existsMsgId` 预查询（3 次取连接/提交 → 1 次） |
| 优化 2 | 群扇出批量入箱（随优化 1 生效）；连接池 10→30 做了对照实验，**无收益**（H2 写事务串行化），已恢复默认 10 并把结论写入 benchmark |
| 压测报告 | `docs/benchmark.md`：环境、参数、结果表、瓶颈分析、优化前后对比、结论与局限、复现命令 |

### 实测结果（本机，详见 benchmark.md）

| 场景 | 结果 |
|---|---|
| connections 200 | 100% 成功，20.6s，客户端 609 线程 / 24.3MB 堆 |
| throughput 10×10×10s | 优化前 65 msg/s → 优化后 **83 msg/s（+27.7%）**；P99 54ms → 69ms（低负载波动） |
| throughput 40×20×10s | **582 msg/s**，P50 1ms / P95 3ms / P99 8ms，丢失 0.08% |
| fanout 50 人 ×3 | 优化前 avg 19.7ms/max 43ms → 优化后 **avg 16.0ms/max 36ms（-18.8%）** |
| stability 50×20s（每秒断 5%） | 重连 36 次，失败 **0**，客户端 172 线程 / 30.7MB 堆 |

### 验证记录

| 检查项 | 结果 |
|---|---|
| `mvn clean verify` | BUILD SUCCESS；im-common 15 + im-server 21 + im-client 13 + im-bench 1(E2E) = **50** 个测试全绿 |

### 已知限制

- 未实现可选的 NIO 传输与虚拟线程模式（协议/net 层已按可替换设计，留待后续）。
- 连接容量实测到 200；500/1000/2000 需更高配置机器 + `ulimit -n`/`-Xss`，命令已写入 benchmark.md。
- 稳定性只跑 20s（命令支持 `--duration 600` 做 10 分钟长稳）。
- 客户端与服务端同机，绝对吞吐偏低；结论以相对对比为准。

---

## 阶段 3-B：客户端完整功能 ✅

**日期**：2026-10-25 · **范围**：仅 `im-client`（可靠接收、好友、群聊、历史、重连、文件、体验打磨）

### 完成内容

| 项 | 说明 |
|---|---|
| 可靠接收 | `ReceiptService`：收到 `PRIVATE_MSG`/`GROUP_MSG`/`OFFLINE_MSG_PUSH` 自动回 `DELIVER_ACK`，**200ms 合并成一批**，按 `msgId` 去重（4096 容量窗口） |
| 好友 | 「好友」标签页 + 添加好友对话框；在线好友排前、离线置灰；双击开始私聊 |
| 群聊 | 「群聊」标签页 + 建群/按群号加群对话框；群聊面板右侧成员列表（在线圆点）；群消息显示发送者昵称 |
| 离线/历史 | 登录后 `OFFLINE_MSG_PUSH` 进入对应会话并计入未读；打开会话加载最近 **50** 条，滚到顶部按 `beforeId` 游标加载更早消息 |
| 断线重连 | `ConnectionManager`：指数退避（1s→2s→4s…上限 30s，最多 10 次），状态栏显示「重连中（第 N 次）」；重连后用 token `RELOGIN` 自动重登，成功刷新界面并补收离线；token 失效/次数耗尽回登录页；断线期间发送的消息直接标记失败可重发 |
| 文件 | 聊天面板「发送文件」（JFileChooser）→ 后台上传 + 进度条 + 取消；消息以 `[[FILE]]{...}` 约定承载；接收方点「下载」选择保存位置，后台下载 + 进度 + 取消，完成后 **校验 MD5** 并可打开所在文件夹 |
| 体验打磨 | 会话未读数徽标、窗口标题未读总数、系统托盘新消息通知（不支持时静默降级）、空状态提示、错误统一友好弹窗 |
| 测试 | 13 个客户端测试：登录/私聊、重发幂等、请求超时、ACK 超时、关闭失败、**去重+批量确认**、**重连自动重登**、**文件进度/MD5/取消**；仍为 headless（`-Djava.awt.headless=true`），不创建窗口 |

### 验证记录

| 检查项 | 结果 |
|---|---|
| `mvn clean verify` | BUILD SUCCESS；im-common 15 + im-server 21 + im-client 13，共 **49** 个测试全绿 |
| 可执行 jar | `im-client.jar` 含 FlatLaf 与全部依赖 |

### 手动演示清单（3 个客户端覆盖全部功能）

准备：`mvn -q clean verify`，启动服务端
`java -jar im-server/target/im-server.jar --port 8080 --file-port 8081`

1. **启动 3 个客户端**：开三个终端执行 `java -jar im-client/target/im-client.jar`，分别注册并登录 `alice`、`bob`、`carol`。
2. **好友**：alice 在「好友」页点「添加好友」输入 bob、carol；查看列表在线好友在前；关闭 carol 客户端后刷新，carol 置灰。
3. **私聊**：alice 双击 bob → 输入消息回车：右侧气泡、状态「发送中→已发送」；bob 端收到并自动滚到底部，未打开会话时「会话」出现未读徽标与托盘通知。
4. **群聊**：alice 在「群聊」页「建群」得到群号；bob、carol 用「加群」输入群号加入；alice 双击群 → 右侧成员列表显示 3 人及在线状态；alice 发群消息，bob/carol 收到且气泡上方显示发送者昵称。
5. **离线投递**：关闭 bob 客户端；alice 向 bob 连发 3 条；bob 重新登录 → 3 条按序到达并计入未读；bob 打开会话后服务端收件箱被清空（再次登录不再重推）。
6. **历史分页**：在 alice–bob 会话中发送 60+ 条消息，重新打开该会话 → 只见最近 50 条；滚到顶部继续向上加载更早消息。
7. **文件传输**：alice 在私聊面板点「发送文件」选择任意文件 → 进度条走动；bob 收到文件气泡，点「下载」选择保存路径 → 进度完成 → 提示 MD5 校验通过 → 选择「打开所在文件夹」；中途点「取消」可中止。
8. **断线重连**：停掉服务端 → 三个客户端状态栏显示「重连中（第 N 次）」；此时 alice 发消息显示「发送失败，点击重发」；重启服务端 → 自动重连并重登，状态恢复「已恢复」；bob 登录期间产生的离线消息自动补收。
9. **顶号**：用 alice 再开一个客户端登录 → 前一个 alice 弹窗「账号已在别处登录」并回到登录页。
10. **错误提示**：错误密码、空用户名、重复注册、非法群号均有中文提示。

### 已知问题（阶段 3-B 范围内接受）

- 离线消息缺少「群标记」：服务端收件箱未存 group_id，客户端统一按发送者私聊会话展示（需协议扩展，阶段 4 处理）。
- 文件上传按整文件读入内存（≤ 配置上限），超大文件需要流式改造。
- 群聊暂不支持发文件（协议 `FILE_UPLOAD` 仅带单个接收方）。
- 收到群消息时若成员列表尚未加载，历史/实时消息的昵称可能回退为「用户 id」，刷新群成员后恢复。

---

## 阶段 3-A：客户端网络层 + 登录 + 私聊 ✅

**日期**：2026-10-25 · **范围**：仅 `im-client`（net/service/ui 三层 + FlatLaf 界面 + 测试）

### 完成内容

| 项 | 说明 |
|---|---|
| `net` 层 | `ImConnection`：连接、读线程、**有界写队列 + 写线程**、心跳（30s PING，连续无响应判死）、状态机（DISCONNECTED→CONNECTING→CONNECTED→AUTHENTICATED）与状态监听器；`RequestManager`：`requestId→CompletableFuture`，默认 **10s 超时**、断开时统一失败；`EventDispatcher`：按 `MessageType` 注册监听器 |
| `service` 层 | `AuthService`（注册/登录/退出，成功即切换 AUTHENTICATED）、`ChatService`（发送私聊并跟踪状态：MSG_ACK→已发送、超时→失败、重发复用原 msgId）、`ContactService`（在线用户）；全部返回 `CompletableFuture`，对 UI 只暴露异步 API |
| 主题/字体 | FlatLaf 浅色主题；字体优先 Microsoft YaHei → PingFang SC → Noto Sans CJK SC → SimHei → Dialog 回退 |
| `LoginFrame` | 表单校验、登录中禁用按钮、错误提示、记住用户名（Preferences）；成功后进入 `MainFrame` |
| `RegisterDialog` | 用户名/密码/确认校验，异步注册，成功后提示 |
| `MainFrame` | 左侧「会话 / 在线用户」标签页（**在线圆点 + 未读数**）、右侧 CardLayout 聊天面板、底部连接状态、标题显示当前用户；被踢下线弹窗后回到登录页 |
| `ChatPanel` | 气泡（自己靠右、对方靠左）、时间、发送状态（发送中/已发送/失败可点重发）、**回车发送 / Shift+回车换行**、新消息自动滚到底部 |
| 线程规则 | 所有跨线程 UI 更新统一走 `ui.UiThread`；EDT 不做网络/磁盘 IO，网络线程不直接碰 Swing |
| 兼容门面 | `service.ImService` 保留为同步门面，供 `im-bench` 复用（bench 不可修改） |
| 测试 | 7 个客户端测试：注册/登录成功、错误密码、私聊收发、重发复用 msgId、请求超时、ACK 超时、关闭时挂起请求失败；测试以 test scope 依赖 im-server，在随机端口启动真实服务端，并设置 `-Djava.awt.headless=true`，不创建任何窗口 |

### 验证记录

| 检查项 | 结果 |
|---|---|
| `mvn clean verify` | BUILD SUCCESS；im-common 15 + im-server 21 + im-client 7，共 43 个测试全绿 |
| 可执行 jar | `im-client.jar` 4.1 MB（含 FlatLaf），`--help` 正常输出 |

### 手动验证步骤（GUI）

1. `mvn -q clean verify`
2. 启动服务端：`java -jar im-server/target/im-server.jar --port 8080 --file-port 8081`
3. 启动客户端 A：`java -jar im-client/target/im-client.jar --user alice` → 登录页点「注册新账号」创建 alice，再登录
4. 启动客户端 B：`java -jar im-client/target/im-client.jar --user bob` → 注册并登录 bob
5. 在 A 的「在线用户」页双击 bob → 右侧出现聊天面板 → 输入文字回车发送：气泡靠右、状态由「发送中」变「已发送」；B 端气泡靠左并自动滚到底部；B 未打开该会话时左侧「会话」出现未读数
6. 顶号验证：再启动一个客户端用 alice 登录 → 第一个 alice 窗口弹窗提示后回到登录页
7. Shift+回车可在输入框换行；断开服务端后发送，状态显示「发送失败，点击重发」，点击可重发

### 已知问题（阶段 3-A 范围内接受）

- 自动重连与 token 重登尚未接线（`ImConnection` 状态机与心跳已就绪，阶段 3-B 完成）。
- 会话历史、好友/群组界面、文件收发不在本阶段范围。
- 桌面通知、消息搜索、未读数持久化未实现。

---

## 阶段 2-B：可靠投递、好友、群组、文件服务 ✅

**日期**：2026-10-25 · **范围**：仅 `im-server`（新增 db/repo/service 层、文件服务、DemoClient、集成测试）

### 完成内容

| 项 | 说明 |
|---|---|
| 持久化 | H2（MySQL 兼容模式）+ HikariCP + JDBC DAO；`schema.sql` 对 H2/MySQL 通用；表：`im_user`、`friend`、`chat_group`、`group_member`、`message`、`offline_message`、`file_meta` |
| 可靠投递 | `msg_id` 唯一索引幂等（重发直接回 ACK，不重复入库）→ 落 `message` → 写 `offline_message` 收件箱 → 在线即时推送 → `DELIVER_ACK` 删除记录；未确认断线则重登重推（至少一次） |
| 离线消息 | 登录/重登后按 id 升序分批（100 条/批）推送，确认一批推下一批 |
| 好友 | `ADD_FRIEND` 事务内双向写入；`FRIEND_LIST` 带实时在线状态 |
| 群组 | `GROUP_CREATE` 事务内建群并拉创建者入群；`GROUP_JOIN` 幂等；`GROUP_LIST`/`GROUP_MEMBERS`（带在线状态）；`GROUP_MSG` 校验成员身份，扇出（除发送者）并给离线成员入箱 |
| 历史 | `HISTORY` 按会话分页：默认 50、上限 100、`beforeId<=0` 为首页；私聊只查自己参与的会话，群聊校验成员身份（越权回 2002） |
| 文件服务 | 独立端口 + 独立线程池；连接先 `RELOGIN_REQ` 认证；上传流式写临时文件、边写边算 MD5、校验通过后重命名并写 `file_meta`；下载仅限上传者/接收方，支持 `offset` 续传；大小上限、目录、线程数均可配 |
| DemoClient | 新增群聊（create/join/list/members/send）、历史、文件上传下载命令 |
| 测试 | 21 个集成测试（可靠投递 5、好友群组 6、历史 4、文件 6），连同 im-common 15 个共 36 个 |

### 本阶段关键决策

1. **文件端口认证**：`FileMessages` 的 DTO 未带 token，为满足"FILE_UPLOAD 校验 token"且不修改 `im-common`（本阶段约束），文件端口复用一个 `RELOGIN_REQ` 握手完成认证，后续帧沿用会话身份；已写入 `protocol.md` 9.1。
2. **上传分块帧**：复用 `FILE_UPLOAD_REQ` 类型码，首帧为申请、后续帧带 `fileId/offset/eof/data`（Base64），以是否含 `fileId` 区分；`im-common` 未改动，负载结构在 `im-server` 的 `FileDto` 中定义并记录到协议文档。
3. **读线程池**：读线程阻塞在 socket 上，固定大小会耗尽；改为 `SynchronousQueue + max=maxConnections`（按需建线程、到上限拒绝）。

### 验证记录

| 检查项 | 结果 |
|---|---|
| `mvn clean verify` | BUILD SUCCESS；im-common 15 + im-server 21，全部通过 |
| 可靠投递测试 | 同 msgId 重发 `message` 仅 1 行；离线 3 条按 m1/m2/m3 顺序推送、确认后收件箱清 0；未确认断线重登再收 2 条 |
| 群组测试 | 非成员发群消息回 `2002`；成员收到、发送者不收；离线成员登录后收到入箱消息 |
| 历史测试 | 5 条消息按 2+2+1 分页、倒序与游标正确；非对话方私聊历史为空；非成员查群历史回 `2002`；默认 50 条 |
| 文件测试 | 上传下载 MD5 一致；offset=150 续传得到尾部字节；超限回 `3002`；第三方下载回 `2002`；非法 token 认证失败 |
| DemoClient 冒烟 | 注册登录 → 建群 `groupId=1` → 群列表显示 `1 项目组 members=1` |

### 手动验证

```bash
mvn -q clean verify
java -jar im-server/target/im-server.jar --port 8080 --file-port 8081
java -cp im-server/target/im-server.jar com.im.server.demo.DemoClient --port 8080 --file-port 8081 --user alice
# 命令：/friend add bob  /group create 项目组  /group send 1 你好  /file send <toUserId> ./a.txt  /history <peerId> [1|2]
```

### 已知问题（阶段 2-B 范围内接受）

- 文件分块帧尚未进 `im-common` 的 DTO（受"只改 im-server"约束），阶段 3 迁移到 `im-client` 时统一收口。
- 服务端尚无空闲检测线程（`heartbeat-seconds` 仅配置就位）；客户端自动重连/心跳留待阶段 2-A 的客户端工作。
- 群消息扇出为串行写库；超大群需要批量插入与异步化（阶段 5 性能优化）。
- 历史消息按 `message.id` 倒序，未做"会话表 + 未读数"建模。

---

## 阶段 1：工程骨架 + 通信协议 ✅

**日期**：2026-10-24

### 完成内容

| 项 | 状态 | 说明 |
|---|---|---|
| Maven 多模块 | ✅ | `im-parent` 聚合 4 个子模块，父 POM 统一依赖/插件版本 |
| 可执行 fat jar | ✅ | server/client/bench 均由 shade 打成含 Main-Class 的 jar |
| 协议层 | ✅ | `MessageType`（38 种消息）、`Packet`、`PacketCodec`（帧编解码）、`ErrorCode`（17 个码）、`JsonUtil` |
| 消息 DTO | ✅ | 账号、消息、联系人、群组、文件、系统六组，按域归档 |
| 协议文档 | ✅ | `docs/protocol.md`：帧格式、字段表、示例、错误码、4 张 Mermaid 时序图、客户端实现清单 |
| 单元测试 | ✅ | 15 个用例：往返、粘包、半包（1/3 字节慢速流）、超大帧、魔数、版本、未知类型、空流、截断、帧头读取 |
| 客户端分层 | ✅ | `net`（ImConnection）/ `service`（ImService）/ `ui`（Swing 占位） |
| 服务端骨架 | ✅ | Acceptor + 有界读线程池；每会话有界发送队列 + 写线程；PBKDF2；顶号；离线分批推送 |
| 工程配套 | ✅ | `.gitignore`、README、`server.properties`、`client.properties`、`scripts/*.bat` 与 `*.sh` |

### 与初版实现的差异（重要）

初版按任务描述自行拟定了帧格式（magic 4B `0x494D3031`、上限 8 MiB、消息类型子集），
读取总纲 `agent.md` 后**已全部按总纲修正**：

| 项 | 初版 | 修正后（总纲） |
|---|---|---|
| 帧头 | magic 4B + version + type + length | **magic 2B `0xCAFE` + version + type + length** |
| 帧上限 | 8 MiB | **1 MiB** |
| 消息类型 | 13 种 | **38 种**（账号/心跳/消息/联系人/群组/文件/系统），编码分段固定 |
| 请求配对 | 无 | **requestId + 响应原样带回** |
| 错误码 | 自定义 | **对齐总纲 1001/1002/2001/2002/3001**，其余按段扩展 |
| 压测模块 | 独立实现 | **复用 im-client 的 net 层** |

### 验证记录（阶段 1）

| 检查项 | 结果 |
|---|---|
| `mvn -q clean verify` | BUILD SUCCESS；`Tests run: 15, Failures: 0` |
| 服务端启动 | `java -jar im-server.jar --port 18082` 正常监听 |
| 客户端 | 自动注册 + 登录成功 |
| 压测冒烟 | 2 连接 × 20 条 = 40 条，错误 0；avg 3.08ms，p99 38ms |

---

## 阶段 2-A 计划（下一步，客户端侧）

- `ImConnection` 自动重连（指数退避 1s→30s）+ 心跳（30s PING）
- token 重登（断线后用 `RELOGIN_REQ` 恢复）
- 好友/群组/历史的客户端 service API 与 Swing 界面接入

## 阶段 3 计划

- Swing 完整界面（登录、主窗口、会话/好友/群聊标签页）
- 文件传输迁入 `im-client`，DTO 收口到 `im-common`
- 压测报告与性能基线（连接数 / 吞吐 / P99）

---

## 历史

| 阶段 | 状态 | 日期 |
|---|---|---|
| 阶段 1：工程骨架 + 通信协议 | ✅ | 2026-10-24 |
| 阶段 2-B：可靠投递、好友、群组、文件服务 | ✅ | 2026-10-25 |
| 阶段 3-A：客户端网络层 + 登录 + 私聊 | ✅ | 2026-10-25 |
| 阶段 3-B：客户端完整功能 | ✅ | 2026-10-25 |
| 阶段 4：端到端测试、压测与优化 | ✅ | 2026-10-25 |
