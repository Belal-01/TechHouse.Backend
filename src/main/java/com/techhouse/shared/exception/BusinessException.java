package com.techhouse.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

@Getter
public class BusinessException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Map<String, String> fieldErrors;

    public BusinessException(String message) {
        this(HttpStatus.BAD_REQUEST, "BUSINESS_ERROR", message, null);
    }

    public BusinessException(String code, String message) {
        this(HttpStatus.BAD_REQUEST, code, message, null);
    }

    public BusinessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = HttpStatus.BAD_REQUEST;
        this.fieldErrors = null;
    }

    public BusinessException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public BusinessException(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.code = code;
        this.status = status;
        this.fieldErrors = fieldErrors;
    }

    /** Error attached to one request field, e.g. {@code variants[2].sku}. */
    public static BusinessException onField(HttpStatus status, String code, String field, String message) {
        return new BusinessException(status, code, message, Map.of(field, message));
    }
}
