package com.im.bench;

import com.im.client.net.ImConnection;
import com.im.client.service.AuthService;
import com.im.client.service.ChatService;
import com.im.client.service.ChatService.PendingMessage;
import com.im.client.service.ChatService.Status;
import com.im.client.service.GroupService;
import com.im.common.message.ChatMessages.GroupMsg;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 压测入口（复用 im-client 的 net 层，所有客户端共享心跳调度线程）。
 *
 * <pre>
 * 场景与参数：
 *   connections  --connections 500            逐步建连并登录，统计成功率/耗时
 *   throughput   --pairs 20 --rate 20 --duration 30   每对按固定速率互发，统计吞吐/P50/P95/P99/丢失率
 *   fanout       --members 100 --messages 5   100 人群，一条消息全部送达耗时
 *   stability    --connections 100 --duration 60      持续跑，期间随机断开 5% 再重连
 * 通用参数：--host 127.0.0.1 --port 8080 --size 64
 * </pre>
 */
public final class BenchMain {

    private static final ScheduledExecutorService HEARTBEAT = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "bench-hb");
        t.setDaemon(true);
        return t;
    });

    private static String host = "127.0.0.1";
    private static int port = 8080;
    private static int payloadSize = 64;

    private BenchMain() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = "throughput";
        int connections = 500;
        int pairs = 20;
        int rate = 20;
        int duration = 30;
        int members = 100;
        int messages = 5;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--scenario" -> scenario = args[++i];
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--connections" -> connections = Integer.parseInt(args[++i]);
                case "--pairs" -> pairs = Integer.parseInt(args[++i]);
                case "--rate" -> rate = Integer.parseInt(args[++i]);
                case "--duration" -> duration = Integer.parseInt(args[++i]);
                case "--members" -> members = Integer.parseInt(args[++i]);
                case "--messages" -> messages = Integer.parseInt(args[++i]);
                case "--size" -> payloadSize = Integer.parseInt(args[++i]);
                case "--help", "-h" -> {
                    System.out.println("用法: --scenario connections|throughput|fanout|stability [参数]");
                    return;
                }
                default -> System.out.println("忽略未知参数: " + args[i]);
            }
        }
        try {
            switch (scenario) {
                case "connections" -> runConnections(connections);
                case "throughput" -> runThroughput(pairs, rate, duration);
                case "fanout" -> runFanout(members, messages);
                case "stability" -> runStability(connections, duration);
                default -> System.out.println("未知场景: " + scenario);
            }
        } finally {
            HEARTBEAT.shutdownNow();
        }
    }

    // ---------- 场景 a：连接容量 ----------

    private static void runConnections(int count) throws Exception {
        List<Client> clients = new ArrayList<>();
        long start = System.nanoTime();
        int ok = 0;
        int failed = 0;
        for (int i = 0; i < count; i++) {
            try {
                clients.add(Client.create("conn_" + System.nanoTime() + "_" + i));
                ok++;
            } catch (Exception e) {
                failed++;
                if (failed <= 5) {
                    System.out.println("connect fail #" + i + ": " + e.getMessage());
                }
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Runtime runtime = Runtime.getRuntime();
        System.out.printf("scenario=connections target=%d success=%d failed=%d successRate=%.2f%% elapsed=%.2fs "
                        + "clientThreads=%d heapUsedMB=%.1f%n",
                count, ok, failed, 100.0 * ok / count, seconds, threads.getThreadCount(),
                (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0));
        // 保持连接 3 秒观察服务端
        Thread.sleep(3000);
        clients.forEach(Client::close);
    }

    // ---------- 场景 b：私聊吞吐与延迟 ----------

    private static void runThroughput(int pairs, int ratePerPair, int durationSec) throws Exception {
        List<Pair> pairList = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            Client a = Client.create("tp_a_" + System.nanoTime() + "_" + i);
            Client b = Client.create("tp_b_" + System.nanoTime() + "_" + i);
            pairList.add(new Pair(a, b));
        }
        payloadSize = Math.max(8, payloadSize);
        String payload = "x".repeat(payloadSize);

        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger sent = new AtomicInteger();
        AtomicInteger received = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        AtomicInteger inflight = new AtomicInteger();
        long endAt = System.nanoTime() + Duration.ofSeconds(durationSec).toNanos();
        long start = System.nanoTime();

        List<Thread> threads = new ArrayList<>();
        for (Pair pair : pairList) {
            pair.receiver.chat.onIncoming(msg -> {
                if (inflight.get() > 0) {
                    inflight.decrementAndGet();
                }
                received.incrementAndGet();
            });
            Thread t = new Thread(() -> {
                long intervalMs = Math.max(1, 1000L / Math.max(1, ratePerPair));
                while (System.nanoTime() < endAt) {
                    long begin = System.nanoTime();
                    if (inflight.incrementAndGet() > 64) {
                        inflight.decrementAndGet();
                        lost.incrementAndGet();
                        sleep(intervalMs);
                        continue;
                    }
                    try {
                        PendingMessage message = pair.sender.chat
                                .sendPrivate(pair.receiver.userId, payload)
                                .get(10, TimeUnit.SECONDS);
                        sent.incrementAndGet();
                        if (message.status() == Status.SENT) {
                            latencies.add((System.nanoTime() - begin) / 1_000_000);
                        } else {
                            errors.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    sleep(intervalMs);
                }
            }, "tp-sender");
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            t.join();
        }
        Thread.sleep(1000); // 等接收端计数稳定
        double seconds = (System.nanoTime() - start) / 1e9;
        long total = sent.get();
        System.out.printf("scenario=throughput pairs=%d ratePerPair=%d durationSec=%d sent=%d received=%d "
                        + "errors=%d throughputMsgPerSec=%.0f lossRate=%.3f%%%n",
                pairs, ratePerPair, durationSec, total, received.get(), errors.get(),
                total / seconds, total == 0 ? 0 : 100.0 * Math.max(0, total - received.get()) / total);
        printPercentiles(latencies);
        pairList.forEach(Pair::close);
    }

    // ---------- 场景 c：群聊扇出 ----------

    private static void runFanout(int memberCount, int messageCount) throws Exception {
        Client owner = Client.create("fo_owner_" + System.nanoTime());
        GroupService groups = new GroupService(owner.connection);
        long groupId = groups.create("bench-group").get(10, TimeUnit.SECONDS).groupId();
        List<Client> members = new ArrayList<>();
        members.add(owner);
        for (int i = 1; i < memberCount; i++) {
            Client member = Client.create("fo_" + System.nanoTime() + "_" + i);
            new GroupService(member.connection).join(groupId).get(10, TimeUnit.SECONDS);
            members.add(member);
        }
        System.out.printf("scenario=fanout groupId=%d members=%d messages=%d%n", groupId, members.size(), messageCount);

        List<Long> deliveryTimes = new ArrayList<>();
        for (int m = 0; m < messageCount; m++) {
            CountDownLatch latch = new CountDownLatch(members.size() - 1);
            for (Client member : members) {
                if (member == owner) {
                    continue;
                }
                Runnable remove = member.connection.on(com.im.common.MessageType.GROUP_MSG, packet -> {
                    try {
                        GroupMsg msg = com.im.common.json.JsonUtil.fromJson(packet.payload(), GroupMsg.class);
                        if (msg.groupId() == groupId) {
                            latch.countDown();
                        }
                    } catch (Exception ignored) {
                        // 忽略
                    }
                });
                member.cleanups.add(remove);
            }
            long begin = System.nanoTime();
            owner.chat.sendGroup(groupId, "fanout-" + m).get(10, TimeUnit.SECONDS);
            if (!latch.await(20, TimeUnit.SECONDS)) {
                System.out.println("  fanout 超时: 收到 " + (members.size() - 1 - latch.getCount()) + "/" + (members.size() - 1));
            }
            deliveryTimes.add((System.nanoTime() - begin) / 1_000_000);
        }
        System.out.printf("scenario=fanout deliveryAvgMs=%.1f deliveryMaxMs=%d%n",
                deliveryTimes.stream().mapToLong(Long::longValue).average().orElse(0),
                deliveryTimes.stream().mapToLong(Long::longValue).max().orElse(0));
        members.forEach(Client::close);
    }

    // ---------- 场景 d：稳定性 ----------

    private static void runStability(int count, int durationSec) throws Exception {
        List<Client> clients = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            clients.add(Client.create("st_" + System.nanoTime() + "_" + i));
        }
        long endAt = System.nanoTime() + Duration.ofSeconds(durationSec).toNanos();
        Random random = new Random(42);
        AtomicInteger reconnects = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicLong exchanges = new AtomicLong();

        while (System.nanoTime() < endAt) {
            int drops = Math.max(1, count * 5 / 100);
            for (int i = 0; i < drops; i++) {
                Client client = clients.get(random.nextInt(clients.size()));
                try {
                    String token = client.token();
                    client.connection.forceDisconnect();
                    Thread.sleep(50);
                    client.connection.reconnect(host, port, Duration.ofSeconds(5));
                    if (token != null && client.auth.relogin(token).get(10, TimeUnit.SECONDS).code() == 0) {
                        reconnects.incrementAndGet();
                    } else {
                        failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }
            Client first = clients.get(random.nextInt(clients.size()));
            Client second = clients.get(random.nextInt(clients.size()));
            if (first != second) {
                try {
                    first.chat.sendPrivate(second.userId, "ping").get(5, TimeUnit.SECONDS);
                    exchanges.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }
            Thread.sleep(1000);
        }
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Runtime runtime = Runtime.getRuntime();
        System.out.printf("scenario=stability clients=%d durationSec=%d reconnects=%d failures=%d exchanges=%d "
                        + "online=%d clientThreads=%d heapUsedMB=%.1f%n",
                count, durationSec, reconnects.get(), failures.get(), exchanges.get(),
                count, threads.getThreadCount(),
                (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0));
        clients.forEach(Client::close);
    }

    // ---------- 工具 ----------

    private static void printPercentiles(List<Long> latencies) {
        if (latencies.isEmpty()) {
            System.out.println("latency: 无样本");
            return;
        }
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);
        long sum = sorted.stream().mapToLong(Long::longValue).sum();
        System.out.printf("latencyMs avg=%.2f p50=%d p95=%d p99=%d max=%d samples=%d%n",
                (double) sum / sorted.size(), percentile(sorted, 50), percentile(sorted, 95),
                percentile(sorted, 99), sorted.get(sorted.size() - 1), sorted.size());
    }

    private static long percentile(List<Long> sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 一对互发用户。 */
    private static final class Pair {
        final Client sender;
        final Client receiver;

        Pair(Client sender, Client receiver) {
            this.sender = sender;
            this.receiver = receiver;
        }

        void close() {
            sender.close();
            receiver.close();
        }
    }

    /** 压测客户端。 */
    private static final class Client {
        final ImConnection connection;
        final AuthService auth;
        final ChatService chat;
        final long userId;
        final List<Runnable> cleanups = new ArrayList<>();

        private Client(ImConnection connection, AuthService auth, ChatService chat, long userId) {
            this.connection = connection;
            this.auth = auth;
            this.chat = chat;
            this.userId = userId;
        }

        static Client create(String username) throws Exception {
            ImConnection connection = new ImConnection(Duration.ofSeconds(10), Duration.ofSeconds(60), 10,
                    HEARTBEAT, false);
            connection.connect(host, port, Duration.ofSeconds(5));
            AuthService auth = new AuthService(connection);
            auth.register(username, "pwd123").get(15, TimeUnit.SECONDS);
            var login = auth.login(username, "pwd123").get(15, TimeUnit.SECONDS);
            if (login.code() != 0) {
                throw new IllegalStateException("登录失败: " + login.message());
            }
            return new Client(connection, auth, new ChatService(connection, login.userId()), login.userId());
        }

        String token() {
            return auth.session() == null ? null : auth.session().token();
        }

        void close() {
            cleanups.forEach(Runnable::run);
            chat.close();
            connection.close();
        }
    }
}
