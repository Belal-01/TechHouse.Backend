package com.techhouse.catalog.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record ProductRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 5000) String description,
        UUID categoryId,
        @Size(max = 50) String sku,
        @Size(max = 64) @Pattern(regexp = "^[0-9A-Za-z-]*$") String barcode,
        /** Selling price in USD. */
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal sellingPrice,
        @Min(0) @Max(120) Integer warrantyMonths,
        Map<@Size(max = 100) String, @Size(max = 500) String> specifications,
        boolean active,
        /** Version the client loaded; a mismatch means someone else edited the product. */
        Integer version
) {}
