package com.techhouse.finance.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for expenses and their categories. */
public final class ExpenseDtos {

    private ExpenseDtos() {
    }

    public record Ref(UUID id, String name) {}

    public record CategoryRequest(@NotBlank @Size(max = 100) String name, Boolean active, Integer version) {}

    /** @param system true for categories the app uses itself (e.g. delivery); they can't be removed or deactivated */
    public record CategoryResponse(UUID id, String name, boolean system, boolean active, int version) {}

    public record ExpenseRequest(
            @NotNull UUID categoryId,
            @NotNull @DecimalMin("0.0001") @DecimalMax("10000000000") BigDecimal amount,
            @NotNull @Pattern(regexp = "USD|SYP") String currency,
            UUID accountId,
            @NotNull LocalDate expenseDate,
            @Size(max = 2000) String description
    ) {}

    public record VoidRequest(@Size(max = 1000) String note) {}

    /**
     * @param account null when it was paid out of a staff advance
     * @param orderId set for order delivery fees (voided together with their order)
     * @param paidBy  the person whose advance paid it, if any
     */
    public record ExpenseResponse(UUID id, LocalDate expenseDate, Ref category, String description, BigDecimal amount,
                                  String currency, BigDecimal exchangeRate, BigDecimal amountUsd, Ref account,
                                  UUID orderId, Ref paidBy, Instant voidedAt, Instant createdAt) {}

    public record CategoryTotal(Ref category, BigDecimal amountUsd, int count) {}

    public record Summary(LocalDate from, LocalDate to, BigDecimal totalUsd, List<CategoryTotal> categories) {}
}
