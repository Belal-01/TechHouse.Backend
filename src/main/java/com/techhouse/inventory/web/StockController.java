package com.techhouse.inventory.web;

import com.techhouse.identity.config.CurrentStore;
import com.techhouse.inventory.application.StockService;
import com.techhouse.inventory.web.dto.StockItem;
import com.techhouse.shared.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/inventory/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    @GetMapping
    public ApiResponse<List<StockItem>> list(@CurrentStore UUID storeId) {
        return ApiResponse.ok(stockService.list(storeId));
    }
}
