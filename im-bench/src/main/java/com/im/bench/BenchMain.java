package com.im.bench;

import com.im.client.service.ImService;
import com.im.common.ErrorCode;
import com.im.common.message.AccountMessages.LoginResponse;
import com.im.common.message.AccountMessages.RegisterResponse;
import com.im.common.message.ChatMessages.MsgAck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 压测入口：多连接并发发送私聊消息，统计端到端 ACK 延迟与吞吐。
 *
 * <p>复用 im-client 的 {@link ImService}（net + service 层），与真实客户端走同一套协议。</p>
 *
 * <pre>
 * 用法: java -jar im-bench.jar [--host 127.0.0.1] [--port 8080]
 *                              [--connections 8] [--messages 500] [--size 64]
 * </pre>
 */
public final class BenchMain {

    private BenchMain() {
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 8080;
        int connections = 8;
        int messages = 500;
        int size = 64;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--connections" -> connections = Integer.parseInt(args[++i]);
                case "--messages" -> messages = Integer.parseInt(args[++i]);
                case "--size" -> size = Integer.parseInt(args[++i]);
                case "--help", "-h" -> {
                    System.out.println("用法: java -jar im-bench.jar [--host] [--port] [--connections] [--messages] [--size]");
                    return;
                }
                default -> System.out.println("忽略未知参数: " + args[i]);
            }
        }

        final String benchHost = host;
        final int benchPort = port;
        final int benchMessages = messages;
        final String payload = "x".repeat(size);
        final int connectionCount = connections;

        System.out.printf("压测: %s:%d 连接数=%d 每连接消息=%d 负载=%dB%n",
                benchHost, benchPort, connectionCount, benchMessages, payload.length());

        List<long[]> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong errors = new AtomicLong();
        CountDownLatch done = new CountDownLatch(connectionCount);
        long start = System.nanoTime();

        for (int c = 0; c < connectionCount; c++) {
            final int index = c;
            Thread t = new Thread(() -> {
                try {
                    runOne(benchHost, benchPort, "bench-" + index, benchMessages, payload, latencies, errors);
                } catch (Exception e) {
                    errors.incrementAndGet();
                    System.err.println("连接 " + index + " 失败: " + e.getMessage());
                } finally {
                    done.countDown();
                }
            }, "bench-" + c);
            t.start();
        }
        done.await();

        double elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0;
        List<Long> all = new ArrayList<>();
        long total = 0;
        synchronized (latencies) {
            for (long[] arr : latencies) {
                for (long v : arr) {
                    all.add(v);
                    total += v;
                }
            }
        }
        all.sort(Long::compareTo);

        System.out.println("----------------------------------------");
        System.out.printf("完成消息: %d, 错误: %d%n", all.size(), errors.get());
        System.out.printf("耗时: %.2fs, 吞吐: %.0f msg/s%n", elapsedSec, elapsedSec > 0 ? all.size() / elapsedSec : 0);
        if (!all.isEmpty()) {
            System.out.printf("延迟 avg=%.2fms p50=%dms p95=%dms p99=%dms max=%dms%n",
                    (double) total / all.size(),
                    percentile(all, 50), percentile(all, 95), percentile(all, 99), all.get(all.size() - 1));
        }
    }

    private static void runOne(String host, int port, String user, int messages, String payload,
                               List<long[]> sink, AtomicLong errors) throws Exception {
        ImService service = new ImService(host, port);
        try {
            service.connect();
            LoginResponse login = service.login(user, "bench");
            if (login.code() != ErrorCode.OK.code()) {
                RegisterResponse reg = service.register(user, "bench");
                if (reg.code() != ErrorCode.OK.code()) {
                    throw new IllegalStateException("注册失败: " + reg.message());
                }
                login = service.login(user, "bench");
            }
            if (login.code() != ErrorCode.OK.code()) {
                throw new IllegalStateException("登录失败: " + login.message());
            }

            long[] arr = new long[messages];
            for (int i = 0; i < messages; i++) {
                long begin = System.nanoTime();
                MsgAck ack = service.sendPrivate(login.userId(), payload + "-" + i);
                if (ack.code() != ErrorCode.OK.code()) {
                    errors.incrementAndGet();
                }
                arr[i] = (System.nanoTime() - begin) / 1_000_000;
            }
            sink.add(arr);
        } finally {
            service.close();
        }
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
