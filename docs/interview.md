# 面试材料（interview）

> 所有回答都对应仓库里的真实实现；数字来自 [benchmark.md](benchmark.md)。

## 1. 一分钟介绍

「这是一个 Java 原生 Socket + Swing 的 C/S 即时通讯项目，四个 Maven 模块：`im-common` 定义协议，
`im-server` 是 BIO 服务端，`im-client` 是 Swing 客户端，`im-bench` 做压测。协议是二进制帧——`0xCAFE`
魔数 + 版本 + 类型 + 长度 + JSON 负载，长度上限 1MiB。服务端一个 Acceptor 加读线程池，每个连接配一个
有界发送队列和写线程；消息可靠投递靠 `msg_id` 唯一索引幂等 + 收件箱表 + `DELIVER_ACK` 确认删除，
离线消息每批 100 条、确认一批推下一批。客户端是网络线程 + EDT 双线程模型，所有跨线程 UI 更新走
`UiThread`。压测在本机 40 对用户持续互发跑到 582 msg/s，P99 8ms。」

## 2. 三分钟介绍

1. **背景与目标**：做一个能演示、能讲清楚每个设计的 IM；约束是不用 Spring/Netty，网络层必须原生 Socket。
2. **协议层**：帧头 8 字节固定长（`magic(2) + version(1) + type(1) + length(4)`），body 是 UTF-8 JSON；
   接收侧 `readFully` 读满，天然解决粘包半包；`type` 分七段编码共 38 种消息；请求带 `requestId`，
   客户端用 `requestId→CompletableFuture` 配对，10 秒超时。
3. **服务端**：`ServerRuntime` 里 1 个 Acceptor + 读线程池（`SynchronousQueue`，按需扩到 `max-connections`）；
   每个 `Connection` 一个 1024 容量写队列和写线程，避免多线程写同一 Socket 造成帧交错，队列满判定慢消费者断开。
4. **可靠投递**：发送方生成 UUID `msgId`；服务端一次事务写 `message` + 批量写 `offline_message`（`msg_id` 唯一索引兜底幂等），
   回 `MSG_ACK`；在线立即推送，接收方回 `DELIVER_ACK` 后删除收件箱记录；离线消息登录后按 id 升序每批 100 条推，
   确认一批推下一批，未确认断线就重推，所以是"至少一次"。
5. **客户端**：net 层负责连接/读写线程/心跳/重连，service 层封装业务并跟踪消息状态（发送中/已发送/失败重发），
   UI 层 FlatLaf + Swing，所有界面更新经 `UiThread.run` 切到 EDT；断线后指数退避重连并用 token `RELOGIN` 自动恢复。
6. **压测与优化**：`im-bench` 四个场景；发现每条消息 3 次独立提交是瓶颈，改成单事务 + JDBC batch 后，
   私聊吞吐 65→83 msg/s（+27.7%），50 人群扇出 19.7→16.0ms（-18.8%）；连接池从 10 调到 30 没收益，
   说明瓶颈在 H2 写事务串行化而不是池大小。

## 3. 高频追问（25 题）

**Q1 粘包和拆包怎么处理？**
帧头定长 8 字节，先读满帧头拿到 `length`，再 `DataInputStream.readFully` 读满 body；不做分隔符解析。
半包时 `readFully` 阻塞到读满，粘包时多出的字节留在流里给下一帧。测试里有"1 字节慢速流""3 字节慢速流"和
"三帧连写"三个用例（`PacketCodecTest`）。

**Q2 为什么用 JSON 而不是 Java 原生序列化？**
①跨语言、可读、抓包能看懂；②Java 序列化绑定类名/`serialVersionUID`，升级字段容易炸；③有反序列化漏洞风险。
代价是体积和 CPU，所以帧头仍是二进制定长，且限制 1MiB 防止大包打爆内存。

**Q3 为什么长度上限 1MiB？**
防止恶意/异常超大帧导致内存耗尽（`length` 直接 `new byte[length]`）。超限在写入端和读取端都拒绝，
错误码 `3001 FRAME_TOO_LARGE`，读端直接断开。

**Q4 BIO 线程模型的上限在哪？怎么改 NIO/Netty？**
每连接 1 读 + 1 写线程，200 连接实测客户端 609 线程；上限受线程栈（默认 1MB/线程）和文件句柄约束。
改法：把 `Connection` 的读/写换成 `Selector` 的 `OP_READ/OP_WRITE`，业务 `Server.handle` 不变；
Netty 则用 `ChannelInboundHandler` + `ByteToMessageDecoder` 解析同一帧格式，`msgId/收件箱`逻辑完全复用。

**Q5 为什么需要应用层心跳？TCP keepalive 不够吗？**
keepalive 默认 2 小时、探测间隔不可控，且只能发现"链路死了"；应用层心跳 30s 一次，能快速发现半开连接、
NAT 超时、对端进程假死，并且 PING/PONG 还能测 RTT。实现上 `ImConnection.heartbeat` 定时发 PING，
`lastInboundAt` 超过 3 个周期判死。

**Q6 重复登录怎么处理？**
`Server.bind()` 里 `online.put(userId, conn)`，若已有旧连接，先给旧连接发 `KICK_NOTIFY(code=2004)` 再关闭，
新连接正常登录；旧客户端收到通知后弹窗回登录页且不再自动重连。测试 `ReliableDeliveryTest`/手动清单覆盖。

**Q7 消息可靠性怎么保证？**
四件事：①发送方生成 `msgId`；②服务端**落库成功才回 `MSG_ACK`**；③在线推送 + 接收方 `DELIVER_ACK` 才删收件箱；
④离线消息登录后重推。任何一步失败，消息仍在收件箱或客户端可重发。

**Q8 幂等怎么做的？**
`message.msg_id` 唯一索引。重发同一 `msgId` 时插入触发唯一键冲突，服务端捕获后直接回 ACK（"重复消息，已忽略"），
不会重复入库、不会重复转发。测试断言 `countByMsgId(msgId)==1`。

**Q9 消息顺序怎么保证？**
单会话内服务端用自增主键 `message.id` 定序；私聊/群聊历史按 `id DESC` 分页，客户端按 id 排序展示。
断线重连后靠 `msgId` 去重 + `id` 排序恢复顺序。多机会破坏这个假设，见 Q24。

**Q10 群聊为什么用写扩散？**
成员数 ≤ 百级时实现简单：一条消息一次事务批量写 N 条收件箱，在线成员立即推；读路径 O(1)。
大群会写放大（100 人群 = 1 条消息 100 行），届时改读扩散（成员拉取）或推拉结合。

**Q11 EDT 是什么？为什么用 invokeLater？**
Swing 的单线程模型：所有组件读写必须在 Event Dispatch Thread。网络线程直接 `setText` 会偶发
`RepaintManager` 异常、界面错乱甚至死锁。`UiThread.run` 内部用 `SwingUtilities.invokeLater`，
EDT 上则直接执行，保证线程规则集中可查（`ui/UiThread`）。

**Q12 为什么不能在多个线程直接写同一个 Socket？**
`write` 不是原子的：两个线程各写"帧头 + 帧体"可能交错，对端读到的就是垃圾帧。项目里每个 `Connection`
只有写线程真正写 Socket，业务线程只往 `LinkedBlockingQueue` 投递；客户端同理（`im-net-writer`）。

**Q13 ConcurrentHashMap 原理？项目里用在哪？**
JDK 8 起是"数组 + 链表/红黑树 + CAS + synchronized 桶锁"：无冲突插入用 CAS，冲突时锁单个桶，读无锁。
项目里 `online`（userId→Connection）、`tokenOwners`、`pending`（requestId→Future）都用它；
会话内"待确认条数"用 `volatile int` + 单连接语义，避免过度加锁。

**Q14 线程池参数怎么定？**
服务端读线程池：核心 8、最大 `max-connections`、`SynchronousQueue`——因为任务是"阻塞读"，必须按需扩线程，
到上限就拒绝（回 `SERVER_BUSY`）。写线程每连接一个，不共用；文件服务固定 `file.threads`（默认 4），
独立端口互不影响。

**Q15 离线消息表为什么要 `(user_id, id)` 联合索引？**
两个核心查询：`WHERE user_id=? ORDER BY id LIMIT 100`（取一批）和 `DELETE WHERE user_id=? AND msg_id=?`。
前者用联合索引直接有序扫，不需要额外排序；后者用 `(user_id, msg_id)`？项目里 `user_id` 前缀 + 回表过滤 msg_id，
因为收件箱按用户删除的量很小，简洁优先。

**Q16 压测怎么做的？瓶颈在哪？**
`im-bench` 四场景（连接/吞吐/扇出/稳定性），复用真实客户端 net 层，连接共享心跳线程避免压测端自己爆线程。
瓶颈：①登录 PBKDF2 120k 迭代，单次约 103ms；②优化前每条消息 3 次事务提交，H2 写串行化；③BIO 线程数随连接线性增长。

**Q17 优化具体改了什么？数据？**
`MessageRepo.insertMessageWithInbox`：一条消息 + 全部收件箱在一次事务内、JDBC batch 写入，并去掉幂等预查询
（改用唯一索引兜底）。私聊吞吐 65→83 msg/s（+27.7%），50 人群扇出 19.7→16.0ms（-18.8%）。
连接池 10→30 无收益，已恢复 10。

**Q18 为什么密码用 PBKDF2？怎么验证？**
加盐 + 高迭代次数，抗彩虹表和暴力破解；`PasswordHasher.hash/verify` 用 `PBKDF2WithHmacSHA256`，
盐随机 16 字节，比较用 `MessageDigest.isEqual` 防时序侧信道。

**Q19 为什么用 HikariCP + 自己写 DAO，不用 ORM？**
项目约束不用 MyBatis；JDBC + `PreparedStatement` 能精确控制事务边界（单消息单事务、批量入箱），
性能可预期，也便于面试讲 SQL 与索引。`Database` 按 JDBC URL 选 `schema.sql`（H2）或 `schema-mysql.sql`（MySQL）。

**Q20 离线消息为什么"确认一批推下一批"？**
避免一次推几千条把客户端 UI 和网络打满，也天然形成流控：客户端 `ReceiptService` 200ms 批量确认，
服务端在 `pendingAcks<=0` 时取下一批。

**Q21 历史消息分页为什么用 `beforeId` 游标而不是 offset？**
`id < beforeId ORDER BY id DESC` 走主键索引，深分页不退化；offset 在数据量大时越翻越慢。
首屏 `beforeId<=0` 表示不设上界，`limit` 默认 50、上限 100。

**Q22 文件传输怎么保证完整性和权限？**
上传：流式写临时文件并计算 MD5，`eof` 时比对大小与 MD5，通过才 rename 为 `fileId` 并写 `file_meta`；
下载：服务端校验"上传者或接收方"才允许，支持 `offset` 续传；客户端落盘后再比对一次 md5。

**Q23 怎么排查线程泄漏/内存泄漏？**
稳定性场景跑完看客户端线程数/堆是否回落；服务端用 `jcmd <pid> Thread.print` 看 `im-reader/writer` 数量是否随连接关闭而下降，
`GC.heap_info` 看堆。项目里 `Connection.close` 负责中断写线程、关闭 socket；`RequestManager.close` 会失败所有挂起请求。

**Q24 要扩展到多台服务器怎么改？**
①接入层按 `userId` 哈希路由（一致性哈希）；②在线状态从本地 `online` Map 迁到 Redis（`user:{id}→node`，带 TTL）；
③跨节点消息走 MQ（Kafka/RocketMQ），收件人在别的节点时由该节点消费并推送；④`msgId` 幂等键进 Redis/DB 唯一索引；
⑤离线收件箱、历史表分库分表（按 user_id 取模）；⑥心跳与顶号改为"节点 + Redis 会话"协同。

**Q25 这个项目你最大的收获/不足？**
收获：把"协议设计—线程模型—可靠投递—压测优化"串成闭环，每个数字都能复现。
不足也很明确：服务端空闲检测未实现、BIO 连接数受线程限制、群聊只支持写扩散、文件整文件入内存；
这些在 DESIGN.md 的「可扩展方向」里都有对应改法。
