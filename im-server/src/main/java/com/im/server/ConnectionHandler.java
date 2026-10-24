package com.im.server;

import com.im.common.Packet;
import com.im.common.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 单连接读循环：读帧 → 交 {@link Server} 处理 → 需要时回写。
 *
 * <p>该 Runnable 由读线程池执行；发送不直接写 Socket，而是投递到 {@link Connection} 的写队列。</p>
 */
public final class ConnectionHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionHandler.class);

    private final Server server;
    private final Connection connection;

    public ConnectionHandler(Server server, Connection connection) {
        this.server = server;
        this.connection = connection;
    }

    @Override
    public void run() {
        try {
            while (connection.isOpen()) {
                Packet packet = connection.read();
                if (packet == null) {
                    break;
                }
                Packet response = server.handle(connection, packet);
                if (response != null) {
                    connection.send(response);
                }
            }
        } catch (ProtocolException e) {
            // 总纲：魔数/版本/长度错误 → 记日志并断开
            log.warn("来自 {} 的协议错误: {}", connection.remoteAddress(), e.getMessage());
        } catch (IOException e) {
            if (connection.isOpen()) {
                log.debug("读取结束 {}: {}", connection.remoteAddress(), e.getMessage());
            }
        } finally {
            server.unregister(connection);
            connection.close();
        }
    }
}
