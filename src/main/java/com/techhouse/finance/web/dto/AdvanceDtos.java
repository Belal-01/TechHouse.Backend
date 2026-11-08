package com.techhouse.finance.web.dto;

import com.techhouse.finance.domain.AdvanceEntry;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Staff people and the money they hold as advances. */
public final class AdvanceDtos {

    private AdvanceDtos() {
    }

    public record PersonRequest(@NotBlank @Size(max = 255) String name, @Size(max = 50) String phone, Boolean active,
                                Integer version) {}

    /** @param owedUsd / owedSyp what the person still owes the shop, per currency */
    public record PersonResponse(UUID id, String name, String phone, boolean active, BigDecimal owedUsd, BigDecimal owedSyp,
                                 int version) {}

    /** Takes money from a box, or gives it back to one. */
    public record CashRequest(
            @NotNull UUID accountId,
            @NotNull @Pattern(regexp = "USD|SYP") String currency,
            @NotNull @DecimalMin("0.0001") @DecimalMax("10000000000") BigDecimal amount,
            @Size(max = 1000) String note
    ) {}

    /** Part of the advance was spent on the shop: it becomes an expense; no cash moves now. */
    public record SpendRequest(
            @NotNull @Pattern(regexp = "USD|SYP") String currency,
            @NotNull @DecimalMin("0.0001") @DecimalMax("10000000000") BigDecimal amount,
            @NotNull UUID categoryId,
            @NotNull LocalDate expenseDate,
            @Size(max = 2000) String description
    ) {}

    /** @param amount signed: + took money, − returned or spent */
    public record EntryResponse(UUID id, AdvanceEntry.Kind kind, String currency, BigDecimal amount,
                                MovementDtos.Ref account, UUID expenseId, String note, Instant createdAt) {}
}
