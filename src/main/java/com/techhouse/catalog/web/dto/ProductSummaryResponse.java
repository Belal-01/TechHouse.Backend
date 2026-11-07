package com.techhouse.catalog.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductSummaryResponse(
        UUID id,
        String name,
        CategorySummary category,
        boolean active,
        int variantCount,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        UUID primaryImageId,
        /** SKU of the only variant; null when the product has several. */
        String sku,
        Instant updatedAt
) {}
