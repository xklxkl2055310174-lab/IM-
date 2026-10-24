package com.im.client;

import com.im.client.service.ImService;
import com.im.common.ErrorCode;
import com.im.common.MessageType;
import com.im.common.json.JsonUtil;
import com.im.common.message.AccountMessages.LoginResponse;
import com.im.common.message.AccountMessages.RegisterResponse;
import com.im.common.message.ChatMessages.MsgAck;
import com.im.common.message.ChatMessages.PrivateMsg;
import com.im.common.message.SystemMessages.ErrorMessage;
import com.im.common.message.SystemMessages.KickNotify;
import com.im.common.message.SystemMessages.ServerBusy;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 客户端入口（阶段 1 为命令行形态）。
 *
 * <p>Swing 界面在阶段 3 实现；当前先用 CLI 验证协议与网络层。
 * 命令：{@code /msg <toUserId> <文本>}、{@code /ping}、{@code /me}、{@code /quit}。</p>
 *
 * <pre>
 * 用法: java -jar im-client.jar [--host 127.0.0.1] [--port 8080] [--user alice] [--pass 123456]
 * </pre>
 */
public final class ClientMain {

    private ClientMain() {
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 8080;
        String user = "user" + (int) (Math.random() * 1000);
        String password = "123456";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--user" -> user = args[++i];
                case "--pass" -> password = args[++i];
                case "--help", "-h" -> {
                    System.out.println("用法: java -jar im-client.jar [--host] [--port] [--user] [--pass]");
                    return;
                }
                default -> System.out.println("忽略未知参数: " + args[i]);
            }
        }

        ImService service = new ImService(host, port);
        try {
            service.connect();
            registerPushListeners(service);

            LoginResponse login = service.login(user, password);
            if (login.code() != ErrorCode.OK.code()) {
                System.out.println("登录失败(" + login.code() + "): " + login.message() + "，尝试注册...");
                RegisterResponse reg = service.register(user, password);
                if (reg.code() != ErrorCode.OK.code()) {
                    System.out.println("注册失败(" + reg.code() + "): " + reg.message());
                    return;
                }
                login = service.login(user, password);
            }
            if (login.code() != ErrorCode.OK.code()) {
                System.out.println("登录失败: " + login.message());
                return;
            }

            System.out.printf("已登录: %s (id=%d) @ %s:%d%n", login.username(), login.userId(), host, port);
            System.out.println("命令: /msg <toUserId> <文本> | /ping | /me | /quit");

            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
            long lastTarget = 0;
            while (true) {
                System.out.print("> ");
                String line = console.readLine();
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equals("/quit") || line.equals("/exit")) {
                    break;
                } else if (line.equals("/me")) {
                    System.out.printf("用户名=%s id=%d token=%s%n", service.username(), service.userId(), service.token());
                } else if (line.equals("/ping")) {
                    System.out.println("PING RTT: " + service.ping() + "ms");
                } else if (line.startsWith("/msg ")) {
                    String rest = line.substring(5).trim();
                    int sp = rest.indexOf(' ');
                    if (sp <= 0) {
                        System.out.println("用法: /msg <toUserId> <文本>");
                        continue;
                    }
                    long to = Long.parseLong(rest.substring(0, sp));
                    String text = rest.substring(sp + 1).trim();
                    lastTarget = to;
                    MsgAck ack = service.sendPrivate(to, text);
                    System.out.printf("ACK msgId=%s code=%d %s%n", ack.msgId(), ack.code(), ack.message());
                } else if (line.startsWith("/")) {
                    System.out.println("未知命令: " + line);
                } else {
                    if (lastTarget == 0) {
                        System.out.println("请先 /msg <toUserId> <文本> 指定接收者");
                        continue;
                    }
                    MsgAck ack = service.sendPrivate(lastTarget, line);
                    System.out.printf("ACK msgId=%s code=%d %s%n", ack.msgId(), ack.code(), ack.message());
                }
            }
        } finally {
            service.close();
            System.out.println("已断开连接。");
        }
    }

    private static void registerPushListeners(ImService service) {
        service.on(MessageType.PRIVATE_MSG, packet -> {
            try {
                PrivateMsg msg = JsonUtil.fromJson(packet.payload(), PrivateMsg.class);
                System.out.println();
                System.out.println("[收到消息] 来自 " + msg.fromUserId() + ": " + msg.content());
                System.out.print("> ");
            } catch (Exception e) {
                System.out.println("[无法解析的消息] " + e.getMessage());
            }
        });
        service.on(MessageType.KICK_NOTIFY, packet -> {
            try {
                KickNotify kick = JsonUtil.fromJson(packet.payload(), KickNotify.class);
                System.out.println();
                System.out.println("[被踢下线] " + kick.code() + ": " + kick.message());
            } catch (Exception ignored) {
                // 忽略解析失败
            }
        });
        service.on(MessageType.SERVER_BUSY, packet -> {
            try {
                ServerBusy busy = JsonUtil.fromJson(packet.payload(), ServerBusy.class);
                System.out.println();
                System.out.println("[服务端繁忙] " + busy.message());
            } catch (Exception ignored) {
                // 忽略解析失败
            }
        });
        service.on(MessageType.ERROR, packet -> {
            try {
                ErrorMessage error = JsonUtil.fromJson(packet.payload(), ErrorMessage.class);
                System.out.println();
                System.out.println("[错误] " + error.code() + ": " + error.message());
            } catch (Exception ignored) {
                // 忽略解析失败
            }
        });
    }
}
