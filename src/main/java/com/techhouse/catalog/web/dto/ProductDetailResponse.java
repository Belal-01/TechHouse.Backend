package com.techhouse.catalog.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProductDetailResponse(
        UUID id,
        String name,
        String description,
        CategorySummary category,
        String sku,
        String barcode,
        BigDecimal sellingPrice,
        Integer warrantyMonths,
        Map<String, String> specifications,
        boolean active,
        List<ImageResponse> images,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
