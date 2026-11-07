package com.techhouse.catalog.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record VariantResponse(
        UUID id,
        List<String> optionValues,
        String sku,
        String barcode,
        BigDecimal sellingPrice,
        UUID imageId,
        boolean active
) {}
