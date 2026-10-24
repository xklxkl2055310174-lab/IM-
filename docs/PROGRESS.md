# 开发进度（PROGRESS）

> 每个阶段结束更新：完成了什么、怎么验证、已知问题、协议变更原因。
> 最新阶段在最上方。

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
