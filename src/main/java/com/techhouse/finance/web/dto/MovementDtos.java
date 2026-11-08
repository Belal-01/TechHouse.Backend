package com.techhouse.finance.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Request and response shapes for the cash ledger screen. */
public final class MovementDtos {

    private MovementDtos() {
    }

    /** Money put into the shop, e.g. the opening balance. */
    public record CapitalRequest(
            @NotNull UUID accountId,
            @NotNull @Pattern(regexp = "USD|SYP") String currency,
            @NotNull @DecimalMin("0.0001") @DecimalMax("10000000000") BigDecimal amount,
            @Size(max = 1000) String note
    ) {}

    /** Same currency, from one box to another (e.g. depositing cash into Sham Cash). */
    public record TransferRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @NotNull @Pattern(regexp = "USD|SYP") String currency,
            @NotNull @DecimalMin("0.0001") @DecimalMax("10000000000") BigDecimal amount,
            @Size(max = 1000) String note
    ) {}

    /** Currency exchange with the real amounts given and received; the other currency is implied. */
    public record ExchangeRequest(
            @NotNull UUID fromAccountId,
            @NotNull @Pattern(regexp = "USD|SYP") String fromCurrency,
            @NotNull @DecimalMin("0.0001") @DecimalMax("10000000000") BigDecimal fromAmount,
            @NotNull UUID toAccountId,
            @NotNull @DecimalMin("0.0001") @DecimalMax("10000000000") BigDecimal toAmount,
            @Size(max = 1000) String note
    ) {}

    /** Correction so the books match what is really in the box; signed. The reason is required. */
    public record AdjustmentRequest(
            @NotNull UUID accountId,
            @NotNull @Pattern(regexp = "USD|SYP") String currency,
            @NotNull @DecimalMin("-10000000000") @DecimalMax("10000000000") BigDecimal amount,
            @NotBlank @Size(max = 1000) String note
    ) {}

    public record Ref(UUID id, String name) {}

    /**
     * @param reference what caused it: type (ORDER, PURCHASE_INVOICE, EXPENSE, PERSON) with id and a readable label
     */
    public record MovementResponse(UUID id, Ref account, String currency, String type, BigDecimal amount,
                                   BigDecimal exchangeRate, Reference reference, UUID group, String note, Instant createdAt) {}

    public record Reference(String type, UUID id, String label) {}
}
