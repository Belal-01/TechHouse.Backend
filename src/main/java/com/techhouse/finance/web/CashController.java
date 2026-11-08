package com.techhouse.finance.web;

import com.techhouse.finance.application.CashAccountService;
import com.techhouse.finance.application.CashMovementService;
import com.techhouse.finance.web.dto.CashAccountRequest;
import com.techhouse.finance.web.dto.CashAccountResponse;
import com.techhouse.finance.web.dto.MovementDtos;
import com.techhouse.identity.config.CurrentStore;
import com.techhouse.shared.web.ApiResponse;
import com.techhouse.shared.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Boxes and their movements. Every write returns the boxes, so the screen shows the new balances at once. */
@RestController
@RequestMapping("/api/v1/admin/finance")
@RequiredArgsConstructor
public class CashController {

    private final CashAccountService accounts;
    private final CashMovementService movements;

    @GetMapping("/accounts")
    public ApiResponse<List<CashAccountResponse>> accounts(@CurrentStore UUID storeId) {
        return ApiResponse.ok(accounts.list(storeId));
    }

    @PostMapping("/accounts")
    public ResponseEntity<ApiResponse<CashAccountResponse>> createAccount(@CurrentStore UUID storeId,
                                                                          @Valid @RequestBody CashAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(accounts.create(storeId, request)));
    }

    @PutMapping("/accounts/{id}")
    public ApiResponse<CashAccountResponse> updateAccount(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                          @Valid @RequestBody CashAccountRequest request) {
        return ApiResponse.ok(accounts.update(storeId, id, request));
    }

    @GetMapping("/movements")
    public ApiResponse<PageResponse<MovementDtos.MovementResponse>> movements(
            @CurrentStore UUID storeId, @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) String currency, @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return ApiResponse.ok(movements.list(storeId, accountId, currency, type, page, size));
    }

    @PostMapping("/movements/capital")
    public ApiResponse<List<CashAccountResponse>> capital(@CurrentStore UUID storeId,
                                                          @Valid @RequestBody MovementDtos.CapitalRequest request) {
        movements.capital(storeId, request);
        return ApiResponse.ok(accounts.list(storeId));
    }

    @PostMapping("/movements/transfer")
    public ApiResponse<List<CashAccountResponse>> transfer(@CurrentStore UUID storeId,
                                                           @Valid @RequestBody MovementDtos.TransferRequest request) {
        movements.transfer(storeId, request);
        return ApiResponse.ok(accounts.list(storeId));
    }

    @PostMapping("/movements/exchange")
    public ApiResponse<List<CashAccountResponse>> exchange(@CurrentStore UUID storeId,
                                                           @Valid @RequestBody MovementDtos.ExchangeRequest request) {
        movements.exchange(storeId, request);
        return ApiResponse.ok(accounts.list(storeId));
    }

    @PostMapping("/movements/adjustment")
    public ApiResponse<List<CashAccountResponse>> adjustment(@CurrentStore UUID storeId,
                                                             @Valid @RequestBody MovementDtos.AdjustmentRequest request) {
        movements.adjustment(storeId, request);
        return ApiResponse.ok(accounts.list(storeId));
    }
}
