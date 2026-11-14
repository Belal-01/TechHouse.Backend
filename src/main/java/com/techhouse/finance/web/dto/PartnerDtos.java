package com.techhouse.finance.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** Company owners and investors. */
public final class PartnerDtos {

    private PartnerDtos() {
    }

    /** @param kind OWNER or INVESTOR; required when creating, ignored when updating (it can't change). */
    public record PartnerRequest(
            @NotBlank @Size(max = 255) String name,
            @Pattern(regexp = "OWNER|INVESTOR") String kind,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal profitPercent,
            @Size(max = 50) String phone,
            @Size(max = 2000) String notes,
            Boolean active,
            Integer version
    ) {}

    /** @param fundedUsd what this person paid towards invoices that weren't voided, in USD */
    public record PartnerResponse(UUID id, String name, String kind, BigDecimal profitPercent, String phone, String notes,
                                  boolean active, BigDecimal fundedUsd, int invoiceCount, int version, UUID advanceProfileId) {}
}
