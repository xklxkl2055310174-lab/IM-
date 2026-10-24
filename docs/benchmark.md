# 压测报告（benchmark）

> 所有数字均由本机真实运行得到；复现命令见文末。机器性能有限，结论以**相对前后对比**为准。

## 1. 测试环境

| 项 | 值 |
|---|---|
| CPU | AMD Ryzen 7 7840H（8 核 16 线程） |
| 内存 | 15.2 GB |
| OS | Windows 11（NT 10.0.26300） |
| JDK | Oracle JDK 21.0.11（LTS） |
| JVM 参数 | 服务端/压测端均使用默认参数（未设 `-Xss` / `-Xmx`） |
| 网络 | 本机回环 127.0.0.1 |
| 存储 | H2 内存库（`jdbc:h2:mem:...;MODE=MySQL`） |

> 说明：客户端与服务端跑在同一台机器上，CPU 争抢会压低绝对吞吐；对比实验保持同样条件。

## 2. 压测工具

`im-bench`（复用 `im-client` 的 net/service 层，所有模拟客户端共享一个心跳调度线程）：

```bash
java -jar im-bench/target/im-bench.jar --scenario <connections|throughput|fanout|stability> [参数]
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `--scenario` | `throughput` | 场景 |
| `--host` / `--port` | `127.0.0.1` / `8080` | 服务端地址 |
| `--connections` | 500 | 连接容量/稳定性场景的连接数 |
| `--pairs` / `--rate` | 20 / 20 | 吞吐场景：用户对数、每对每秒消息数 |
| `--duration` | 30 | 持续时间（秒） |
| `--members` / `--messages` | 100 / 5 | 扇出场景：群成员数、发送条数 |
| `--size` | 64 | 消息体字节数 |

## 3. 结果汇总（优化后）

| 场景 | 参数 | 结果 |
|---|---|---|
| connections | 200 连接 | 成功率 **100%**，耗时 **20.6s**（≈103ms/连接，瓶颈为 PBKDF2），客户端线程 609、堆 24.3MB |
| throughput | 10 对 × 10 条/s × 10s | **83 msg/s**，avg 2.44ms，P50 1ms，P95 4ms，P99 69ms |
| throughput（加压） | 40 对 × 20 条/s × 10s | **582 msg/s**，avg 1.57ms，P50 1ms，P95 3ms，P99 8ms，丢失率 0.08% |
| fanout | 50 人群、3 条 | 全部送达 avg **16.0ms**，max 36ms |
| stability | 50 连接、20s、每秒随机断 5% | 重连 36 次，失败 **0**，客户端线程 172、堆 30.7MB（结束后无持续增长） |

连接数说明：本机为笔记本 + 默认堆栈，200 连接稳定；500/1000/2000 需要上调文件句柄（Windows 为端口/句柄策略，Linux 用 `ulimit -n 65535`）并加 `-Xss256k`，本次未跑满，命令已给出。

## 4. 优化项与前后对比

### 优化 1：单事务写库（消息本体 + 收件箱）

**问题**：优化前每条消息需要 3 次独立连接/提交——`existsMsgId` 查询、插入 `message`、插入 `offline_message`；群聊扇出还要为每个成员再各写一次。

**改动**（`MessageRepo.insertMessageWithInbox`）：一次取连接、`autoCommit=false`，消息与全部收件箱记录在同一事务内写入（收件箱用 JDBC batch），提交一次；幂等改由 `msg_id` 唯一索引兜底，去掉预查询。

| 指标 | 优化前 | 优化后 | 变化 |
|---|---|---|---|
| 私聊吞吐（10 对×10/s） | 65 msg/s | **83 msg/s** | **+27.7%** |
| 群扇出 50 人（3 条） | avg 19.7ms / max 43ms | **avg 16.0ms / max 36ms** | **-18.8%** |

### 优化 2：群扇出批量入箱 + 连接池调参

**改动**：群消息的收件箱插入由"每成员一次写"改为一次 `executeBatch()`（已含在优化 1 的同一事务路径中）；同时把 `db.pool-size` 从 10 调到 30 做对照实验。

| 实验（40 对 × 20/s × 10s） | 结果 |
|---|---|
| pool=10（优化 1 之后） | 582 msg/s，P99 8ms，丢失 0.08% |
| pool=30（优化 1+2 之后） | 581 msg/s，P99 8ms，丢失 0.23% |

**结论：连接池从 10 调到 30 在本机没有收益**（H2 内存库写入串行化，瓶颈不在连接池）。因此**保留 `db.pool-size=10` 为默认**；连接池大小作为可调项记录，而不是"优化"。

> 生产环境（MySQL + 磁盘）连接池的意义更大，届时可用 `--db` 指向 MySQL 做同样对照。

### 未采纳/待验证的方向

- **PBKDF2 迭代次数**：200 次登录耗时 ≈20.6s，单次约 103ms，是登录高峰的主要 CPU 开销。降低迭代次数能线性提速，但削弱口令强度，**默认保持 120000**，仅在压测环境可通过 `security.pbkdf2-iterations` 调低。
- **USER_STATUS_NOTIFY 全量广播**：当前服务端未实现该广播，因此不存在 O(N²) 推送问题；若后续实现，应按好友/群成员定向推送，避免全量。
- **NIO / 虚拟线程**：本阶段未实现（可选进阶项），`im-bench` 与协议已按可替换传输层设计，留待后续。

## 5. 瓶颈分析

1. **登录（PBKDF2）**：单次登录 103ms，主要消耗在 PBKDF2-SHA256 120k 迭代，属预期安全成本；连接风暴时 CPU 打满。
2. **写库**：H2 内存库对写事务串行化，优化前每条消息 3 次提交，吞吐受限于提交次数；优化 1 后降到 1 次，吞吐提升 27.7%。
3. **扇出**：优化前 O(成员数) 次独立写，优化后 1 次批量写，50 人群扇出降低 18.8%。
4. **连接数**：每连接 1 读线程 + 1 写线程 + 客户端 1 心跳线程（共享后减少），200 连接时客户端 609 线程；再往上需要 `-Xss` 与句柄上限支撑，属 BIO 模型的固有成本。

## 6. 结论与局限

- 在单机回环、H2 内存库条件下，私聊可达 **~580 msg/s**（40 对持续发送），P99 8ms；连接建立 100% 成功。
- 两项改动带来可复现的收益：私聊吞吐 +27.7%，50 人群扇出耗时 -18.8%。
- 连接池扩容在本场景无收益，说明瓶颈在写事务串行化而非池大小。
- **局限**：客户端与服务端同机、H2 内存库、默认 JVM 参数、未跑 1000+ 连接与 10 分钟长稳（命令已给出，需更高配置机器）；绝对数字不代表生产容量。

## 7. 复现命令

```bash
# 构建
mvn -q clean verify

# 启动服务端（内存库便于多次重复）
java -jar im-server/target/im-server.jar --port 8080 --file-port 8081 \
     --db "jdbc:h2:mem:bench;MODE=MySQL;DB_CLOSE_DELAY=-1"

# a. 连接容量（逐步建立并登录）
java -jar im-bench/target/im-bench.jar --scenario connections --connections 200

# b. 私聊吞吐与延迟
java -jar im-bench/target/im-bench.jar --scenario throughput --pairs 10 --rate 10 --duration 10
java -jar im-bench/target/im-bench.jar --scenario throughput --pairs 40 --rate 20 --duration 10

# c. 群聊扇出（50 人）
java -jar im-bench/target/im-bench.jar --scenario fanout --members 50 --messages 3

# d. 稳定性（50 连接、20 秒、随机断 5%）
java -jar im-bench/target/im-bench.jar --scenario stability --connections 50 --duration 20
```

大连接数补充命令（Linux，需先 `ulimit -n 65535`）：

```bash
java -Xss256k -jar im-server/target/im-server.jar --port 8080 --file-port 8081
java -jar im-bench/target/im-bench.jar --scenario connections --connections 1000
```
