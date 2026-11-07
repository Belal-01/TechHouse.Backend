package com.techhouse.catalog.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProductRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 5000) String description,
        UUID categoryId,
        @Min(0) @Max(120) Integer warrantyMonths,
        Map<@Size(max = 100) String, @Size(max = 500) String> specifications,
        boolean active,
        @Size(max = 3) List<@NotBlank @Size(max = 50) String> optionNames,
        @NotEmpty @Size(max = 200) List<@Valid VariantRequest> variants,
        /** Version the client loaded; a mismatch means someone else edited the product. */
        Integer version
) {}
