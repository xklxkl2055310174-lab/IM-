package com.im.client;

import com.im.client.net.ImConnection;
import com.im.client.service.AuthService;
import com.im.client.service.FileService;
import com.im.client.support.ServerFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件传输：上传/下载、进度回调、取消、MD5 校验。
 */
class FileServiceTest {

    @Test
    @DisplayName("上传下载进度回调完整，MD5 一致")
    void uploadDownloadWithProgress() throws Exception {
        try (ServerFixture fixture = ServerFixture.start()) {
            ImConnection connection = new ImConnection(Duration.ofSeconds(5), Duration.ofSeconds(30), 3);
            connection.connect("127.0.0.1", fixture.port(), Duration.ofSeconds(3));
            AuthService auth = new AuthService(connection);
            String name = "f_" + System.nanoTime();
            auth.register(name, "pwd123").get(5, TimeUnit.SECONDS);
            auth.login(name, "pwd123").get(5, TimeUnit.SECONDS);

            byte[] data = new byte[200 * 1024];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i * 31);
            }
            String md5 = md5(data);
            Path source = Files.createTempFile("im-upload", ".bin");
            Files.write(source, data);

            FileService files = new FileService("127.0.0.1", fixture.filePort(), auth.session().token());
            AtomicInteger uploadEvents = new AtomicInteger();
            AtomicLong uploadLast = new AtomicLong();
            String fileId = files.upload(source, auth.session().userId(),
                    (done, total) -> {
                        uploadEvents.incrementAndGet();
                        uploadLast.set(done);
                    }, new AtomicBoolean(false)).get(15, TimeUnit.SECONDS);

            assertTrue(fileId != null && !fileId.isBlank());
            assertTrue(uploadEvents.get() > 1, "应多次回调进度");
            assertEquals(data.length, uploadLast.get());

            Path target = Files.createTempFile("im-download", ".bin");
            AtomicInteger downloadEvents = new AtomicInteger();
            AtomicLong downloadLast = new AtomicLong();
            files.download(fileId, target, md5, (done, total) -> {
                downloadEvents.incrementAndGet();
                downloadLast.set(done);
            }, new AtomicBoolean(false)).get(15, TimeUnit.SECONDS);

            assertArrayEquals(data, Files.readAllBytes(target));
            assertTrue(downloadEvents.get() > 1, "应多次回调进度");
            assertEquals(data.length, downloadLast.get());

            connection.close();
        }
    }

    @Test
    @DisplayName("MD5 不一致时下载失败且不落盘")
    void md5MismatchFails() throws Exception {
        try (ServerFixture fixture = ServerFixture.start()) {
            ImConnection connection = new ImConnection(Duration.ofSeconds(5), Duration.ofSeconds(30), 3);
            connection.connect("127.0.0.1", fixture.port(), Duration.ofSeconds(3));
            AuthService auth = new AuthService(connection);
            String name = "f2_" + System.nanoTime();
            auth.register(name, "pwd123").get(5, TimeUnit.SECONDS);
            auth.login(name, "pwd123").get(5, TimeUnit.SECONDS);

            byte[] data = "md5-check".getBytes();
            Path source = Files.createTempFile("im-upload2", ".bin");
            Files.write(source, data);
            FileService files = new FileService("127.0.0.1", fixture.filePort(), auth.session().token());
            String fileId = files.upload(source, auth.session().userId(), null,
                    new AtomicBoolean(false)).get(15, TimeUnit.SECONDS);

            Path target = Path.of(source.getParent().toString(), "im-bad-download.bin");
            Files.deleteIfExists(target);
            ExecutionException error = assertThrows(ExecutionException.class, () -> files.download(
                    fileId, target, "deadbeefdeadbeefdeadbeefdeadbeef", null,
                    new AtomicBoolean(false)).get(15, TimeUnit.SECONDS));
            assertTrue(rootMessage(error).contains("MD5"));
            assertTrue(!Files.exists(target), "校验失败不应留下文件");
            connection.close();
        }
    }

    @Test
    @DisplayName("取消下载会立即失败")
    void cancelDownload() throws Exception {
        try (ServerFixture fixture = ServerFixture.start()) {
            ImConnection connection = new ImConnection(Duration.ofSeconds(5), Duration.ofSeconds(30), 3);
            connection.connect("127.0.0.1", fixture.port(), Duration.ofSeconds(3));
            AuthService auth = new AuthService(connection);
            String name = "f3_" + System.nanoTime();
            auth.register(name, "pwd123").get(5, TimeUnit.SECONDS);
            auth.login(name, "pwd123").get(5, TimeUnit.SECONDS);

            byte[] data = "cancel-me".getBytes();
            Path source = Files.createTempFile("im-upload3", ".bin");
            Files.write(source, data);
            FileService files = new FileService("127.0.0.1", fixture.filePort(), auth.session().token());
            String fileId = files.upload(source, auth.session().userId(), null,
                    new AtomicBoolean(false)).get(15, TimeUnit.SECONDS);

            AtomicBoolean cancel = new AtomicBoolean(true);
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> files.download(fileId, Files.createTempFile("im-cancel", ".bin"),
                            null, null, cancel).get(10, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof java.util.concurrent.CancellationException);
            connection.close();
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? "" : cause.getMessage();
    }

    private static String md5(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
    }
}
