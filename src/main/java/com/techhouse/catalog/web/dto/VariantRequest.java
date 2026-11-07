package com.techhouse.catalog.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record VariantRequest(
        /** Present for an existing variant, absent for a new one. */
        UUID id,
        List<@Size(max = 100) String> optionValues,
        @Size(max = 50) String sku,
        @Size(max = 64) @Pattern(regexp = "^[0-9A-Za-z-]*$") String barcode,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal sellingPrice,
        UUID imageId,
        boolean active
) {}
