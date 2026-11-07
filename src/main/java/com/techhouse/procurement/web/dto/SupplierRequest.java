package com.techhouse.procurement.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SupplierRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String contactInfo,
        @Size(max = 5000) String notes,
        Boolean active,
        Integer version
) {}
