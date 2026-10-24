# IM 即时通讯系统

基于 Java 17 的 C/S 即时通讯系统（Socket + 多线程 + 自定义二进制协议），Maven 多模块。
目标：可运行、可演示、每个设计都能在面试中讲清楚。

## 模块结构

```
im-parent/                聚合 POM，统一依赖与插件版本
├── im-common/            协议契约：MessageType、Packet、PacketCodec、DTO、ErrorCode、JsonUtil
├── im-server/            服务端：Acceptor + 有界读线程池、会话管理、消息路由、离线队列
├── im-client/            net（网络层，不依赖 Swing）/ service（业务 API）/ ui（Swing，阶段 3）
└── im-bench/             压测：复用 im-client 的 net 层，无界面
```

## 环境要求

- JDK 17+
- Maven 3.8+

## 构建

```bash
mvn -q clean verify
```

产物：

| 模块 | 产物 |
|---|---|
| im-server | `im-server/target/im-server.jar` |
| im-client | `im-client/target/im-client.jar` |
| im-bench | `im-bench/target/im-bench.jar` |

## 运行

```bash
# 1. 服务端（默认 8080）
java -jar im-server/target/im-server.jar --port 8080

# 2. 客户端（Swing 图形界面，支持多开）
java -jar im-client/target/im-client.jar --host 127.0.0.1 --port 8080

# 3. 压测
java -jar im-bench/target/im-bench.jar --connections 8 --messages 500 --size 64
```

或使用脚本（Windows `.bat` / Linux、macOS `.sh`）：

```
scripts/start-server.bat 8080
scripts/start-client.bat 127.0.0.1 8080
scripts/start-bench.bat 127.0.0.1 8080 8 500
```

客户端界面：登录页可注册新账号；登录后左侧「会话 / 在线用户」，双击在线用户开始私聊；
回车发送、Shift+回车换行；消息状态显示 发送中/已发送/失败（失败可点击重发）。

## 配置

- `server.properties`：端口、文件端口、线程池、连接上限、发送队列、文件目录/上限、PBKDF2 迭代次数、**数据库 JDBC url（默认 H2 文件模式，可切 MySQL 8）**
- `client.properties`：服务端地址、连接/请求超时、心跳间隔、重连退避

两个文件都在各模块 `src/main/resources` 下，可复制到工作目录覆盖。

## 演示客户端

```bash
java -cp im-server/target/im-server.jar com.im.server.demo.DemoClient --port 8080 --file-port 8081 --user alice
```

命令：`/msg`、`/friend add|list`、`/group create|join|list|members|send`、`/history`、`/file send|get`。

## 文档

- [协议规范](docs/protocol.md)：帧格式、全部消息、错误码、4 张时序图、客户端实现清单
- [开发进度](docs/PROGRESS.md)：阶段计划、交付物、验证记录、已知问题

## 当前进度

阶段 1（骨架与协议）与阶段 2-B（可靠投递、好友、群组、文件服务）已完成；详见 [docs/PROGRESS.md](docs/PROGRESS.md)。
