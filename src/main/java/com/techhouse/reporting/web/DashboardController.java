package com.techhouse.reporting.web;

import com.techhouse.identity.config.CurrentStore;
import com.techhouse.reporting.application.DashboardService;
import com.techhouse.shared.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService service;

    /** @param from / to inclusive days in the store's time zone */
    @GetMapping
    public ApiResponse<DashboardDtos.Dashboard> dashboard(
            @CurrentStore UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.dashboard(storeId, from, to));
    }

    @PutMapping("/profit-share")
    public ApiResponse<DashboardDtos.ProfitShare> updateProfitShare(@CurrentStore UUID storeId,
                                                                   @Valid @RequestBody DashboardDtos.ProfitShareRequest request) {
        return ApiResponse.ok(service.updateProfitShare(storeId, request));
    }

    @GetMapping("/wealth")
    public ApiResponse<DashboardDtos.Wealth> wealth(@CurrentStore UUID storeId) {
        return ApiResponse.ok(service.wealth(storeId));
    }
}
