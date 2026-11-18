package com.serein.chat.api;

/** API 调用异常，kind 与 chat-demo 的 ApiErrorKind 一一对应。 */
public class ApiException extends Exception {

    public static final int HTTP = 0;
    public static final int STREAM = 1;
    public static final int NETWORK = 2;
    public static final int INTERRUPTED = 3;
    public static final int PROTOCOL = 4;

    public final int kind;
    public final int status;
    /** 服务端返回的错误码，用于区分 Key 无效 / 余额不足 / 限流。 */
    public final String code;

    public ApiException(int kind, String message, int status, String code) {
        super(message == null ? "" : message);
        this.kind = kind;
        this.status = status;
        this.code = code;
    }

    public ApiException(int kind, String message, int status) {
        this(kind, message, status, null);
    }

    /** 判断是否应当展示「API Key 无效」类提示。 */
    public boolean isAuthError() {
        if (status == 401 || status == 403) return true;
        return code != null && (code.contains("invalid_api_key") || code.contains("authentication"));
    }

    public boolean isInsufficientBalance() {
        return status == 402 || (code != null && code.contains("insufficient"));
    }

    public boolean isRateLimited() {
        return status == 429;
    }
}
