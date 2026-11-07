package com.techhouse.procurement.web.dto;

import java.time.Instant;
import java.util.UUID;

public record SupplierResponse(
        UUID id,
        String name,
        String contactInfo,
        String notes,
        boolean active,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
