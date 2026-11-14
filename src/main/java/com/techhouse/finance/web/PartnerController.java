package com.techhouse.finance.web;

import com.techhouse.finance.application.PartnerService;
import com.techhouse.finance.web.dto.PartnerDtos;
import com.techhouse.identity.config.CurrentStore;
import com.techhouse.shared.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/finance/partners")
@RequiredArgsConstructor
public class PartnerController {

    private final PartnerService service;

    @GetMapping
    public ApiResponse<List<PartnerDtos.PartnerResponse>> list(@CurrentStore UUID storeId) {
        return ApiResponse.ok(service.list(storeId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PartnerDtos.PartnerResponse>> create(@CurrentStore UUID storeId,
                                                                           @Valid @RequestBody PartnerDtos.PartnerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(storeId, request)));
    }

    @GetMapping("/{id}")
    public ApiResponse<PartnerDtos.PartnerResponse> get(@CurrentStore UUID storeId, @PathVariable UUID id) {
        return ApiResponse.ok(service.get(storeId, id));
    }

    @PutMapping("/{id}")
    public ApiResponse<PartnerDtos.PartnerResponse> update(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                           @Valid @RequestBody PartnerDtos.PartnerRequest request) {
        return ApiResponse.ok(service.update(storeId, id, request));
    }
}
