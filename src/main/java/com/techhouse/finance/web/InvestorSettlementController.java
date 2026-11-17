package com.techhouse.finance.web;

import com.techhouse.finance.application.InvestorSettlementService;
import com.techhouse.identity.config.CurrentStore;
import com.techhouse.shared.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/finance/partners/{id}/settlement")
@RequiredArgsConstructor
public class InvestorSettlementController {

    private final InvestorSettlementService service;

    public record Payout(
            @NotNull @Pattern(regexp = "CAPITAL_PAYOUT|PROFIT_PAYOUT") String kind,
            @NotNull UUID accountId,
            @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @Size(max = 500) String note
    ) {}

    @GetMapping
    public ApiResponse<InvestorSettlementService.Statement> statement(@CurrentStore UUID storeId, @PathVariable UUID id) {
        return ApiResponse.ok(service.statement(storeId, id));
    }

    @PostMapping("/payout")
    public ApiResponse<InvestorSettlementService.Statement> payout(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                                    @Valid @RequestBody Payout request) {
        return ApiResponse.ok(service.payout(storeId, id, request.kind(), request.accountId(), request.amount(), request.note()));
    }
}
