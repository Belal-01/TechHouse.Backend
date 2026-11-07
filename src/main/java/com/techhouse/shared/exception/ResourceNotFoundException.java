package com.techhouse.shared.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resource, UUID id) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", "%s not found: %s".formatted(resource, id));
    }

    public ResourceNotFoundException(String resource, String identifier) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", "%s not found: %s".formatted(resource, identifier));
    }

    /** Not found with a specific code, e.g. {@code PRODUCT_NOT_FOUND}. */
    public ResourceNotFoundException(String code, String resource, UUID id) {
        super(HttpStatus.NOT_FOUND, code, "%s not found: %s".formatted(resource, id));
    }
}
