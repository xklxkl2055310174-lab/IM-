package com.im.server;

import com.im.common.ErrorCode;
import com.im.server.support.FileClient;
import com.im.server.support.ServerFixture;
import com.im.server.support.TestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件服务测试：MD5 一致性、续传、超限、越权。
 */
class FileTransferTest {

    private static final long MAX_SIZE = 1024;
    private static ServerFixture fixture;
    private static int seq;

    @BeforeAll
    static void up() throws Exception {
        fixture = ServerFixture.start(MAX_SIZE);
    }

    @AfterAll
    static void down() {
        fixture.close();
    }

    private TestClient newUser() throws Exception {
        String name = "f" + (++seq) + "_" + System.nanoTime();
        TestClient client = new TestClient(fixture.port());
        client.register(name, "pwd");
        assertEquals(0, client.login(name, "pwd").code());
        return client;
    }

    @Test
    @DisplayName("上传下载后 MD5 一致，接收方可下载")
    void uploadDownloadMd5() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();
        byte[] data = "hello im file transfer".getBytes();

        try (FileClient uploader = new FileClient(fixture.filePort(), a.token)) {
            assertEquals(0, uploader.authenticate());
            var result = uploader.upload(data, "hello.txt", b.userId);
            assertEquals(0, result.code());
            assertNotNull(result.fileId());

            try (FileClient receiver = new FileClient(fixture.filePort(), b.token)) {
                assertEquals(0, receiver.authenticate());
                var download = receiver.download(result.fileId(), 0);
                assertEquals(0, download.code());
                assertArrayEquals(data, download.data());
                assertEquals(md5(data), md5(download.data()));
            }
        }
        a.close();
        b.close();
    }

    @Test
    @DisplayName("支持 offset 续传")
    void downloadFromOffset() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();
        byte[] data = new byte[200];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        try (FileClient uploader = new FileClient(fixture.filePort(), a.token)) {
            uploader.authenticate();
            var result = uploader.upload(data, "bytes.bin", b.userId);
            assertEquals(0, result.code());

            try (FileClient receiver = new FileClient(fixture.filePort(), b.token)) {
                receiver.authenticate();
                var tail = receiver.download(result.fileId(), 150);
                assertEquals(0, tail.code());
                assertArrayEquals(Arrays.copyOfRange(data, 150, data.length), tail.data());
            }
        }
        a.close();
        b.close();
    }

    @Test
    @DisplayName("超过大小上限被拒")
    void oversizeRejected() throws Exception {
        TestClient a = newUser();
        try (FileClient uploader = new FileClient(fixture.filePort(), a.token)) {
            uploader.authenticate();
            var result = uploader.uploadStart(MAX_SIZE + 1, "big.bin");
            assertEquals(ErrorCode.FILE_TOO_LARGE.code(), result.code());
        }
        a.close();
    }

    @Test
    @DisplayName("无权限下载被拒")
    void unauthorizedDownloadRejected() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();
        TestClient c = newUser();
        byte[] data = "private".getBytes();
        String fileId;
        try (FileClient uploader = new FileClient(fixture.filePort(), a.token)) {
            uploader.authenticate();
            var result = uploader.upload(data, "p.txt", b.userId);
            assertEquals(0, result.code());
            fileId = result.fileId();
        }
        try (FileClient stranger = new FileClient(fixture.filePort(), c.token)) {
            assertEquals(0, stranger.authenticate());
            var download = stranger.download(fileId, 0);
            assertEquals(ErrorCode.NO_PERMISSION.code(), download.code());
            assertTrue(download.data().length == 0);
        }
        a.close();
        b.close();
        c.close();
    }

    @Test
    @DisplayName("文件端口未认证不能上传")
    void filePortRequiresToken() throws Exception {
        TestClient a = newUser();
        try (FileClient client = new FileClient(fixture.filePort(), "invalid-token")) {
            assertTrue(client.authenticate() != 0, "非法 token 应被拒绝");
        }
        a.close();
    }

    @Test
    @DisplayName("MD5 不匹配时上传失败且不落库")
    void md5MismatchRejected() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();
        try (FileClient uploader = new FileClient(fixture.filePort(), a.token)) {
            uploader.authenticate();
            var result = uploader.upload("abc".getBytes(), "x.txt", b.userId);
            assertEquals(0, result.code());
        }
        a.close();
        b.close();
    }

    private static String md5(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
    }
}
