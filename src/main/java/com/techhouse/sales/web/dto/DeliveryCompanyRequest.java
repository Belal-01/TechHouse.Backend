package com.techhouse.sales.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeliveryCompanyRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String contactInfo,
        Boolean active,
        Integer version
) {}
