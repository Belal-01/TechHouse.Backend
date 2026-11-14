package com.techhouse.procurement.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Says whose money an unassigned outside payment was; the parts must add up to that payment. */
public record AssignFundingRequest(@NotEmpty @Size(max = 10) List<@Valid Part> parts) {

    /** @param amount in the payment's currency */
    public record Part(
            @NotNull @Pattern(regexp = "OWNER|INVESTOR") String source,
            @NotNull UUID partnerId,
            @NotNull @DecimalMin("0.0001") BigDecimal amount
    ) {}
}
