package com.techhouse.identity.config;

import java.util.List;
import java.util.UUID;

public record JwtAuthDetails(
        UUID userId,
        String role,
        List<UUID> storeIds
) {}
