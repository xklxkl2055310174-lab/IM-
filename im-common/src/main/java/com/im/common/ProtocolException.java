package com.im.common;

/**
 * 协议层异常：携带 {@link ErrorCode}，便于调用方区分失败原因。
 */
public class ProtocolException extends Exception {

    private final ErrorCode errorCode;

    public ProtocolException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ProtocolException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
