package com.techhouse.catalog.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProductDetailResponse(
        UUID id,
        String name,
        String description,
        CategorySummary category,
        Integer warrantyMonths,
        Map<String, String> specifications,
        List<String> optionNames,
        boolean active,
        List<VariantResponse> variants,
        List<ImageResponse> images,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
