package com.techhouse.procurement.web;

import com.techhouse.identity.config.CurrentStore;
import com.techhouse.procurement.application.SupplierService;
import com.techhouse.procurement.web.dto.SupplierRequest;
import com.techhouse.procurement.web.dto.SupplierResponse;
import com.techhouse.shared.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/procurement/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @GetMapping
    public ApiResponse<List<SupplierResponse>> list(@CurrentStore UUID storeId,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(required = false) Boolean active) {
        return ApiResponse.ok(supplierService.list(storeId, q, active));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierResponse> get(@CurrentStore UUID storeId, @PathVariable UUID id) {
        return ApiResponse.ok(supplierService.get(storeId, id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SupplierResponse>> create(@CurrentStore UUID storeId,
                                                                @Valid @RequestBody SupplierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(supplierService.create(storeId, request)));
    }

    @PutMapping("/{id}")
    public ApiResponse<SupplierResponse> update(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                @Valid @RequestBody SupplierRequest request) {
        return ApiResponse.ok(supplierService.update(storeId, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentStore UUID storeId, @PathVariable UUID id) {
        supplierService.delete(storeId, id);
        return ResponseEntity.noContent().build();
    }
}
