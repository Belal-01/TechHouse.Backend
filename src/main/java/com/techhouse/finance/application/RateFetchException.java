package com.techhouse.finance.application;

public class RateFetchException extends RuntimeException {

    public RateFetchException(String message) {
        super(message);
    }

    public RateFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
