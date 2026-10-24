package com.im.client.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 假服务端：接受连接并吞掉所有字节，从不响应。
 *
 * <p>用于验证 RequestManager 的超时与 ChatService 的 ACK 超时（不依赖真实服务端行为）。</p>
 */
public final class FakeServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Thread acceptor;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();

    private FakeServer(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        this.acceptor = new Thread(this::acceptLoop, "fake-server-acceptor");
        this.acceptor.setDaemon(true);
    }

    public static FakeServer start() throws IOException {
        FakeServer server = new FakeServer(new ServerSocket(0));
        server.acceptor.start();
        return server;
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                clients.add(socket);
                Thread reader = new Thread(() -> drain(socket), "fake-server-reader");
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void drain(Socket socket) {
        try (InputStream in = socket.getInputStream()) {
            byte[] buffer = new byte[4096];
            while (in.read(buffer) >= 0) {
                // 丢弃，不回复
            }
        } catch (IOException ignored) {
            // 连接关闭
        }
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // 忽略
        }
        clients.forEach(socket -> {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
        });
    }
}
