# Java 即时通讯系统（IM）—— 项目总纲

## 1. 角色与目标
你是资深 Java 工程师。请从零实现一个可运行、可演示、每个设计都能在面试中讲清楚的 C/S 即时通讯系统，用作简历项目。
定位：Java 原生 Socket + 多线程 + Swing，重点体现「多线程并发、TCP 长连接、性能与稳定性」。
最终交付：可一键启动的服务端和客户端、单元与集成测试、压测工具和真实压测报告、设计文档与面试材料。

## 2. 简历原文（实现必须能支撑每一句）

**基于 Java 的即时通讯（IM）系统｜Java 17 · Socket · 多线程 · Swing · H2/MySQL**
技术栈：Java 17、原生 Socket（BIO）、多线程/线程池、自定义二进制协议（JSON 负载）、HikariCP + H2/MySQL、Swing + FlatLaf、JUnit 5

- **协议与网络**：自定义应用层帧协议（`0xCAFE` 魔数 + 版本 + 类型 + 长度 + UTF-8 JSON，单帧上限 1MiB），
  定义 38 种消息、17 个错误码；接收侧用 `readFully` 处理粘包/半包，请求以 `requestId` 与 `CompletableFuture`
  配对、10s 超时；15 个协议单测覆盖往返、粘包、半包、超限、魔数/版本/未知类型。
- **服务端并发模型**：1 个 Acceptor + 按需扩展的读线程池，每个连接配容量 1024 的有界发送队列与独立写线程，
  队列满判定慢消费者并断开；200 连接压测 100% 成功，客户端侧实测 609 线程。
- **可靠投递**：客户端生成 UUID `msgId`，服务端在**一次事务**内写消息本体并用 JDBC batch 写全部收件箱，
  `msg_id` 唯一索引兜底幂等；`MSG_ACK` 落库确认 + `DELIVER_ACK` 删除收件箱，离线消息按 100 条/批
  "确认一批推下一批"，断线未确认自动重推（至少一次）。
- **客户端**：Swing + FlatLaf 四标签页界面（登录/好友/群聊/在线用户），网络线程与 EDT 严格分离、所有 UI 更新
  经 `UiThread` 统一切换；200ms 批量 `DELIVER_ACK` + `msgId` 去重；指数退避（1s→30s）重连 + token 自动重登；
  文件上传下载带进度、取消与 MD5 校验。
- **性能与压测**：自研 `im-bench` 四场景（连接/吞吐/扇出/稳定性）。40 对用户持续互发达 **582 msg/s**
  （P50 1ms / P95 3ms / P99 8ms）；单事务写库优化使私聊吞吐 **65→83 msg/s（+27.7%）**、
  50 人群扇出 **19.7→16.0ms（-18.8%）**；稳定性 50 连接 20s、随机断 5% 重连 36 次 0 失败。

## 3. 技术约束
- JDK 17，Maven 多模块。
- 不使用 Spring、Netty、MyBatis 等框架；网络层必须基于 java.net.Socket / ServerSocket（只有进阶阶段允许 java.nio）。
- 第三方库白名单：Jackson、SLF4J + Logback、HikariCP、H2、MySQL Connector/J、FlatLaf、JUnit 5、AssertJ、Awaitility。新增依赖要在 docs/PROGRESS.md 写明理由。
- 数据库：默认 H2 文件模式（MySQL 兼容模式，免安装），改配置即可切换到 MySQL 8；提供 docker-compose.yml 启动 MySQL；建表脚本要兼容两者（必要时分两份）。
- 标识符用英文，注释和文档用中文；核心类写类注释，说明职责和它运行在哪个线程。
- 可调参数（端口、线程池大小、心跳间隔、帧上限、文件目录、PBKDF2 迭代次数等）都放在 server.properties / client.properties。

## 4. 模块结构
- im-common：协议与公共代码（Packet、MessageType、编解码、DTO、错误码），是前后端唯一的契约
- im-server：接入、会话管理、消息路由、持久化、心跳、文件服务
- im-client：net（网络层，不依赖 Swing）、service（业务 API）、ui（Swing 界面）
- im-bench：压测与端到端测试，复用 im-client 的 net 层，无界面
- docs/：protocol.md、DESIGN.md、benchmark.md、interview.md、PROGRESS.md
- scripts/：服务端、客户端启动脚本（.sh 和 .bat 各一份）

## 5. 功能范围
1. 账号：注册、登录（返回 token）、token 重登、退出；密码用 PBKDF2 加盐哈希；同一账号重复登录时踢掉旧连接（旧端收到 KICK_NOTIFY）。
2. 好友：按用户名加好友（双向、免审批）；好友列表显示实时在线状态。
3. 在线用户：在线用户列表；用户上下线时推送 USER_STATUS_NOTIFY（推送范围可配置为 all / friends，压测时对比）。
4. 私聊和群聊：文本消息实时推送；建群、凭群号加群、群列表、群成员（带在线状态）。
5. 可靠投递：客户端为每条消息生成 msgId（UUID）；服务端落库后给发送方回 MSG_ACK，再为每个接收方写一条收件箱记录（offline_message），接收方在线就立即推送；接收方回 DELIVER_ACK 后删除对应记录。msg_id 唯一索引保证重发不重复入库；客户端按 msgId 去重。
6. 离线消息：登录或重登成功后，把收件箱里的消息按 id 顺序分批推送（每批 100 条），客户端确认一批再推下一批。
7. 历史消息：打开会话时拉取最近 50 条，可向上翻页（按 id 游标）。
8. 文件传输：私聊发文件，走独立的文件端口和线程池；流式分块传输，带进度和 MD5 校验，单文件上限 100 MB；先上传到服务端，再用一条聊天消息通知接收方，接收方离线后也能下载。
9. 心跳与重连：客户端每 30s 发 PING；服务端记录会话最后活跃时间并定时扫描，90s 没有任何数据就断开并清理；客户端断线后指数退避重连（1s、2s、4s……最长 30s），并用 token 自动重登。

## 6. 通信协议
- 帧格式（大端序）：magic 2B（0xCAFE）｜version 1B｜type 1B｜length 4B｜body（UTF-8 JSON）
- 用 DataInputStream.readFully 读满，处理半包和粘包；length 上限 1 MB；超限、魔数或版本错误时记日志并断开。
- 请求类消息带 requestId，响应原样带回，客户端据此匹配请求与响应。
- 消息类型（具体编码在阶段 1 确定，写入 docs/protocol.md）：
  - 账号：REGISTER、LOGIN、RELOGIN、LOGOUT（请求 + 响应）
  - 心跳：PING、PONG
  - 消息：PRIVATE_MSG、GROUP_MSG（发送和推送共用）、MSG_ACK、DELIVER_ACK、OFFLINE_MSG_PUSH、HISTORY
  - 联系人：FRIEND_LIST、ADD_FRIEND、ONLINE_USERS、USER_STATUS_NOTIFY
  - 群组：GROUP_CREATE、GROUP_JOIN、GROUP_LIST、GROUP_MEMBERS
  - 文件（只在文件端口使用）：FILE_UPLOAD、FILE_DOWNLOAD
  - 其他：KICK_NOTIFY、SERVER_BUSY、ERROR（code + message）
- 统一错误码枚举，如 1001 用户名已存在、1002 密码错误、2001 未登录、2002 无权限、3001 帧过大。

## 7. 服务端设计要点
- 线程模型（BIO）：1 个 Acceptor 线程；每个连接从有界线程池（ThreadPoolExecutor，自定义线程名）分到一个读线程；每个会话配一个有界发送队列和一个写线程，避免多线程同时写同一个 Socket 导致帧交错，也避免慢客户端拖住别的线程；发送队列满了判定为慢消费者并断开；连接数超过上限时回 SERVER_BUSY 后关闭。
- 传输层与业务层解耦：业务 Handler 只依赖 Connection 接口（send / close / 属性），不直接碰 Socket，以便之后换成 NIO 实现。
- 分发：Map<MessageType, PacketHandler> 策略模式；未登录的连接只允许 REGISTER、LOGIN、RELOGIN、PING。
- 会话：ConcurrentHashMap<userId, Session>；上线、下线、踢人都要并发安全，尤其是旧连接的清理逻辑不能把新登录的会话删掉。
- 安全：fromUserId 一律取自服务端会话，不信任客户端传值；SQL 全部用 PreparedStatement；文件按 fileId 存储，原文件名只存数据库（防路径穿越）；下载前校验权限。
- 持久化：JDBC + HikariCP + DAO。表：user、friend、chat_group、group_member、message（msg_id 唯一索引）、offline_message（(user_id, id) 联合索引）、file_meta。
- 可观测：Logback 输出到控制台和滚动文件，MDC 带 userId 和连接 id；ServerMetrics（LongAdder）统计连接数、在线数、收发消息数、错误数，每 30s 打印一次。
- 优雅停机：ShutdownHook 依次停止接收新连接、通知客户端、关闭会话、关闭线程池和连接池。

## 8. 客户端设计要点
- 线程规则（硬性）：EDT 上禁止任何网络或磁盘 IO；网络线程禁止直接操作 Swing 组件，一律经 SwingUtilities.invokeLater；耗时操作用 SwingWorker 或 CompletableFuture。
- 网络层：ImConnection 负责连接、读线程、写队列线程、心跳、状态机（DISCONNECTED → CONNECTING → CONNECTED → AUTHENTICATED）和自动重连；RequestManager 维护 requestId → CompletableFuture，默认 10s 超时；按消息类型注册监听器来分发事件。
- 界面：FlatLaf 主题；登录、注册窗口；主窗口左侧标签页（会话、好友、群聊、在线用户），显示在线状态和未读数；右侧聊天面板（CardLayout 切换会话），消息气泡区分自己和对方，显示时间和发送状态，按服务端消息 id 排序；底部状态栏显示连接状态。
- UI 状态（会话、未读数、联系人）只在 EDT 上读写（线程封闭），不加锁。
- 同一台机器能同时开多个客户端，方便演示。

## 9. 工作方式（每个阶段都必须遵守）
1. 开始前先读本文件和 docs/PROGRESS.md，输出本阶段计划（要新建、修改的文件和关键设计），然后直接实现；只有遇到真正的需求歧义才提问。
2. 只做当前阶段的内容，不要提前堆后续功能。
3. 阶段结束前必须运行 mvn -q clean verify 并全部通过；禁止删除或弱化测试来"通过"；压测数据必须真实运行得到，禁止编造。
4. im-common 是契约：改协议必须同步更新 docs/protocol.md，并在 PROGRESS.md 写明原因。
5. 阶段结束时更新 docs/PROGRESS.md（完成了什么、怎么验证、已知问题），然后 git commit（Conventional Commits，如 feat(server): ...）。
6. 最后用不超过 5 行告诉我：做了什么、我怎么手动验证。
