package com.techhouse.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Standard response envelope. {@code code} is a stable key the frontend maps to a localized message;
 * {@code error} is a developer-facing text; {@code fieldErrors} maps request field paths
 * (e.g. {@code variants[2].sku}) to messages.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        String error,
        String code,
        Map<String, String> fieldErrors
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, null);
    }

    public static <T> ApiResponse<T> success(T data) {
        return ok(data);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message, null, null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, null, message, code, null);
    }

    public static <T> ApiResponse<T> error(String code, String message, Map<String, String> fieldErrors) {
        return new ApiResponse<>(false, null, message, code,
                fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors);
    }
}
