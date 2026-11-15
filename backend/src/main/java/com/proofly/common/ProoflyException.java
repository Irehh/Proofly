package com.proofly.common;

import org.springframework.http.HttpStatus;

public class ProoflyException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode errorCode;

    public ProoflyException(HttpStatus status, ErrorCode errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public ProoflyException(HttpStatus status, ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
