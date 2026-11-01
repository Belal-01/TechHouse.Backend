package com.techhouse.identity.web.dto;

import java.util.UUID;

public record UserDto(
        UUID id,
        String email,
        String displayName,
        String role
) {}
