package com.techhouse.sales.web;

import com.techhouse.identity.config.CurrentStore;
import com.techhouse.sales.application.DeliveryCompanyService;
import com.techhouse.sales.web.dto.DeliveryCompanyRequest;
import com.techhouse.sales.web.dto.DeliveryCompanyResponse;
import com.techhouse.shared.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/sales/delivery-companies")
@RequiredArgsConstructor
public class DeliveryCompanyController {

    private final DeliveryCompanyService service;

    @GetMapping
    public ApiResponse<List<DeliveryCompanyResponse>> list(@CurrentStore UUID storeId) {
        return ApiResponse.ok(service.list(storeId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DeliveryCompanyResponse>> create(@CurrentStore UUID storeId,
                                                                       @Valid @RequestBody DeliveryCompanyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(storeId, request)));
    }

    @PutMapping("/{id}")
    public ApiResponse<DeliveryCompanyResponse> update(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                       @Valid @RequestBody DeliveryCompanyRequest request) {
        return ApiResponse.ok(service.update(storeId, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentStore UUID storeId, @PathVariable UUID id) {
        service.delete(storeId, id);
        return ResponseEntity.noContent().build();
    }
}
