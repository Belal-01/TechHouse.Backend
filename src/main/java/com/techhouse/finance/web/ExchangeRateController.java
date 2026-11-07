package com.techhouse.finance.web;

import com.techhouse.finance.application.ExchangeRateService;
import com.techhouse.finance.web.dto.CurrentRateResponse;
import com.techhouse.finance.web.dto.ExchangeRateResponse;
import com.techhouse.finance.web.dto.ManualRateRequest;
import com.techhouse.identity.config.CurrentStore;
import com.techhouse.shared.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/finance/exchange-rates")
@RequiredArgsConstructor
public class ExchangeRateController {

    private final ExchangeRateService service;

    @GetMapping("/current")
    public ApiResponse<CurrentRateResponse> current(@CurrentStore UUID storeId) {
        return ApiResponse.ok(service.current(storeId));
    }

    @GetMapping
    public ApiResponse<List<ExchangeRateResponse>> history(@CurrentStore UUID storeId,
                                                           @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(service.history(storeId, days));
    }

    @PostMapping("/refresh")
    public ApiResponse<CurrentRateResponse> refresh(@CurrentStore UUID storeId) {
        return ApiResponse.ok(service.refresh(storeId));
    }

    @PutMapping("/today")
    public ApiResponse<ExchangeRateResponse> setManual(@CurrentStore UUID storeId,
                                                       @Valid @RequestBody ManualRateRequest request) {
        return ApiResponse.ok(service.setManual(storeId, request));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<ExchangeRateResponse> approve(@CurrentStore UUID storeId, @PathVariable UUID id) {
        return ApiResponse.ok(service.approve(storeId, id));
    }
}
