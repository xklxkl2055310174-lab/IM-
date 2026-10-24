package com.im.common.message;

/**
 * 文件域 DTO：仅在文件端口（{@code file.port}）上使用。
 *
 * <p>流程：先 {@code FILE_UPLOAD_REQ} 申请，服务端回 {@code FILE_UPLOAD_RESP} 带 fileId，
 * 之后流式分块上传（分块仍以本协议帧承载，块大小见 {@code file.chunk.size}），
 * 最后一块的响应带总字节数；下载同样是请求后分块回传。</p>
 */
public final class FileMessages {

    private FileMessages() {
    }

    /**
     * 文件上传请求（{@code FILE_UPLOAD_REQ}）。
     *
     * @param requestId 请求标识
     * @param fileName  原始文件名（仅用于展示，落盘用 fileId，防路径穿越）
     * @param fileSize  文件字节数
     * @param md5       内容 MD5 十六进制摘要（完整性校验）
     */
    public record FileUploadRequest(String requestId, String fileName, long fileSize, String md5) {
    }

    /**
     * 文件上传响应（{@code FILE_UPLOAD_RESP}）。
     *
     * @param requestId     对应请求 id
     * @param code          错误码，0 成功
     * @param message       描述
     * @param fileId        服务端分配的文件 id
     * @param uploadedBytes 已接收字节数（完成时为总字节数）
     */
    public record FileUploadResponse(String requestId, int code, String message,
                                     String fileId, long uploadedBytes) {
    }

    /**
     * 文件下载请求（{@code FILE_DOWNLOAD_REQ}）。
     *
     * @param requestId 请求标识
     * @param fileId    文件 id
     */
    public record FileDownloadRequest(String requestId, String fileId) {
    }

    /**
     * 文件下载响应（{@code FILE_DOWNLOAD_RESP}）：按块连续下发，{@code eof=true} 为最后一块。
     *
     * @param requestId  对应请求 id
     * @param code       错误码，0 成功；2006 文件不存在
     * @param message    描述
     * @param fileId     文件 id
     * @param fileName   原始文件名
     * @param totalBytes 文件总字节数
     * @param offset     本块起始偏移
     * @param eof        是否最后一块
     * @param data       本块数据（UTF-8 安全编码，通常为 Base64；错误响应可为空）
     */
    public record FileDownloadResponse(String requestId, int code, String message, String fileId,
                                       String fileName, long totalBytes, int offset, boolean eof, String data) {
    }
}
