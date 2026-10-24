package com.im.client.file;

/**
 * 文件端口负载结构（与 im-server 的 {@code FileDto} 字段一致，协议见 docs/protocol.md 第 9 节）。
 */
public final class FileDtos {

    private FileDtos() {
    }

    /** 上传申请。 */
    public record UploadStart(String requestId, String fileName, long fileSize, String md5, Long toUserId) {
    }

    /** 上传分块。 */
    public record UploadChunk(String requestId, String fileId, int offset, boolean eof, String data) {
    }

    /** 上传结果。 */
    public record UploadResult(String requestId, int code, String message, String fileId, long uploadedBytes) {
    }

    /** 下载申请。 */
    public record DownloadStart(String requestId, String fileId, long offset) {
    }

    /** 下载分块。 */
    public record DownloadChunk(String requestId, int code, String message, String fileId, String fileName,
                                long totalBytes, int offset, boolean eof, String data) {
    }
}
