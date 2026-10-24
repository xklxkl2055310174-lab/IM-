# 开发进度（PROGRESS）

> 每个阶段结束更新：完成了什么、怎么验证、已知问题、协议变更原因。
> 最新阶段在最上方。

---

## 阶段 1：工程骨架 + 通信协议 ✅

**日期**：2026-10-24

### 完成内容

| 项 | 状态 | 说明 |
|---|---|---|
| Maven 多模块 | ✅ | `im-parent` 聚合 4 个子模块，父 POM 统一依赖/插件版本 |
| 可执行 fat jar | ✅ | server/client/bench 均由 shade 打成含 Main-Class 的 jar |
| 协议层 | ✅ | `MessageType`（38 种消息）、`Packet`、`PacketCodec`（帧编解码）、`ErrorCode`（17 个码）、`JsonUtil` |
| 消息 DTO | ✅ | 账号、消息、联系人、群组、文件、系统六组，按域归档（`AccountMessages` 等） |
| 协议文档 | ✅ | `docs/protocol.md`：帧格式、字段表、示例、错误码、4 张 Mermaid 时序图、客户端实现清单 |
| 单元测试 | ✅ | 15 个用例：往返、粘包、半包（1/3 字节慢速流）、超大帧（写/读/负数）、魔数、版本、未知类型、空流、截断、帧头读取 |
| 客户端分层 | ✅ | `net`（ImConnection，含 requestId↔Future 配对）/ `service`（ImService）/ `ui`（Swing 占位） |
| 服务端骨架 | ✅ | Acceptor + 有界读线程池；每会话有界发送队列 + 写线程；PBKDF2 密码哈希；顶号 `KICK_NOTIFY`；离线消息 100 条/批、确认后推下一批 |
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
| 压测模块 | 独立实现 | **复用 im-client 的 net 层**（`im-bench` 依赖 `im-client`） |

### 验证记录

| 检查项 | 结果 |
|---|---|
| `mvn -q clean verify` | BUILD SUCCESS；`Tests run: 15, Failures: 0, Errors: 0` |
| 服务端启动 | `java -jar im-server.jar --port 18082` 正常监听，日志输出连接/注册/登录/断开 |
| 客户端 | 自动注册 + 登录成功：`已登录: smoke2 (id=1003)` |
| 压测冒烟 | 2 连接 × 20 条 = 40 条，错误 0；avg 3.08ms，p50 1ms，p99 38ms（本机回环，仅基线） |

### 手动验证

```bash
mvn -q clean verify
java -jar im-server/target/im-server.jar --port 8080
java -jar im-client/target/im-client.jar --user alice   # 自动注册并登录
java -jar im-bench/target/im-bench.jar --connections 8 --messages 500
```

### 已知问题 / 阶段 1 限制

- 账号与消息全部在内存，重启即失；密码已用 PBKDF2 加盐哈希（阶段 2 落库）。
- 客户端未实现自动重连与健康检查（状态机与 token 重登已就绪，阶段 2 接线）。
- 文件传输仅定义消息与流程，文件端口与分块实现留待阶段 4。
- Swing 界面为占位窗口（阶段 3 实现登录/主界面）。
- 好友、群组、历史消息的消息类型与 DTO 已定义，服务端在阶段 1 回 `ERROR(1005)`。

---

## 阶段 2 计划（下一步）

- 持久化：H2（MySQL 兼容模式）+ HikariCP + DAO；`user`、`friend`、`message`、`offline_message`、`file_meta`
- 可靠投递落库：`msg_id` 唯一索引、收件箱 `(user_id, id)` 联合索引
- 客户端自动重连（指数退避）+ token 重登 + 心跳
- 好友、在线用户、上下线通知

---

## 历史

| 阶段 | 状态 | 日期 |
|---|---|---|
| 阶段 1：工程骨架 + 通信协议 | ✅ | 2026-10-24 |
