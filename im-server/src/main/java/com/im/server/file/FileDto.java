package com.im.server.file;

/**
 * 文件端口专用负载结构（与 {@code docs/protocol.md} 第 9 节一致）。
 *
 * <p>说明：文件消息复用 {@code FILE_UPLOAD_REQ/FILE_UPLOAD_RESP/FILE_DOWNLOAD_REQ/FILE_DOWNLOAD_RESP}
 * 四个类型码；上传首帧为 {@link UploadStart}，后续帧为 {@link UploadChunk}（以是否含 fileId 区分）。</p>
 */
public final class FileDto {

    private FileDto() {
    }

    /**
     * 上传申请。
     *
     * @param requestId 请求标识
     * @param fileName  原始文件名
     * @param fileSize  文件字节数
     * @param md5       内容 MD5（十六进制，32 字符）
     * @param toUserId  接收方用户 id（决定下载权限）
     */
    public record UploadStart(String requestId, String fileName, long fileSize, String md5, Long toUserId) {
    }

    /**
     * 上传分块。
     *
     * @param requestId 请求标识
     * @param fileId    服务端分配的文件 id
     * @param offset    本块起始偏移（必须连续）
     * @param eof       是否最后一块
     * @param data      Base64 数据块
     */
    public record UploadChunk(String requestId, String fileId, int offset, boolean eof, String data) {
    }

    /**
     * 上传结果。
     *
     * @param requestId     对应请求
     * @param code          错误码
     * @param message       描述
     * @param fileId        文件 id
     * @param uploadedBytes 已接收/总字节数
     */
    public record UploadResult(String requestId, int code, String message, String fileId, long uploadedBytes) {
    }

    /**
     * 下载申请。
     *
     * @param requestId 请求标识
     * @param fileId    文件 id
     * @param offset    起点偏移（续传）
     */
    public record DownloadStart(String requestId, String fileId, long offset) {
    }

    /**
     * 下载分块。
     *
     * @param requestId  对应请求
     * @param code       错误码
     * @param message    描述
     * @param fileId     文件 id
     * @param fileName   原始文件名
     * @param totalBytes 文件总字节数
     * @param offset     本块起始偏移
     * @param eof        是否最后一块
     * @param data       Base64 数据块
     */
    public record DownloadChunk(String requestId, int code, String message, String fileId, String fileName,
                                long totalBytes, int offset, boolean eof, String data) {
    }
}
