package com.techhouse.shared.exception;

import java.util.UUID;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resource, UUID id) {
        super("NOT_FOUND", "%s not found: %s".formatted(resource, id));
    }

    public ResourceNotFoundException(String resource, String identifier) {
        super("NOT_FOUND", "%s not found: %s".formatted(resource, identifier));
    }
}
