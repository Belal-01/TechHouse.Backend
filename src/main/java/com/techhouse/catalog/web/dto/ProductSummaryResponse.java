package com.techhouse.catalog.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductSummaryResponse(
        UUID id,
        String name,
        CategorySummary category,
        boolean active,
        BigDecimal sellingPrice,
        UUID primaryImageId,
        String sku,
        Instant updatedAt
) {}
