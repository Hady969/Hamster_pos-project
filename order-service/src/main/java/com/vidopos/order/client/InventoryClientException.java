package com.vidopos.order.client;

public class InventoryClientException extends RuntimeException {

    private final boolean retryable;
    private final Integer httpStatus;

    public InventoryClientException(String message, boolean retryable, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }
}
