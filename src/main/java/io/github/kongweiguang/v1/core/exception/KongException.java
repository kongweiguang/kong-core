package io.github.kongweiguang.v1.core.exception;

/**
 * 自定义异常
 *
 * @author kongweiguang
 */
public class KongException extends RuntimeException {
    public KongException(String message, Throwable cause) {
        super(message, cause);
    }

    public KongException(Throwable cause) {
        super(cause);
    }
}
