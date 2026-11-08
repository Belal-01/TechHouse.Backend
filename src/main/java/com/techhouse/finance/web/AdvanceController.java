package com.techhouse.finance.web;

import com.techhouse.finance.application.AdvanceService;
import com.techhouse.finance.web.dto.AdvanceDtos;
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
@RequestMapping("/api/v1/admin/finance/people")
@RequiredArgsConstructor
public class AdvanceController {

    private final AdvanceService service;

    @GetMapping
    public ApiResponse<List<AdvanceDtos.PersonResponse>> list(@CurrentStore UUID storeId) {
        return ApiResponse.ok(service.people(storeId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdvanceDtos.PersonResponse>> create(@CurrentStore UUID storeId,
                                                                          @Valid @RequestBody AdvanceDtos.PersonRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createPerson(storeId, request)));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdvanceDtos.PersonResponse> get(@CurrentStore UUID storeId, @PathVariable UUID id) {
        return ApiResponse.ok(service.person(storeId, id));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdvanceDtos.PersonResponse> update(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                          @Valid @RequestBody AdvanceDtos.PersonRequest request) {
        return ApiResponse.ok(service.updatePerson(storeId, id, request));
    }

    @GetMapping("/{id}/entries")
    public ApiResponse<List<AdvanceDtos.EntryResponse>> entries(@CurrentStore UUID storeId, @PathVariable UUID id) {
        return ApiResponse.ok(service.entries(storeId, id));
    }

    @PostMapping("/{id}/withdraw")
    public ApiResponse<AdvanceDtos.PersonResponse> withdraw(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                            @Valid @RequestBody AdvanceDtos.CashRequest request) {
        return ApiResponse.ok(service.withdraw(storeId, id, request));
    }

    @PostMapping("/{id}/return")
    public ApiResponse<AdvanceDtos.PersonResponse> giveBack(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                            @Valid @RequestBody AdvanceDtos.CashRequest request) {
        return ApiResponse.ok(service.giveBack(storeId, id, request));
    }

    @PostMapping("/{id}/spend")
    public ApiResponse<AdvanceDtos.PersonResponse> spend(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                         @Valid @RequestBody AdvanceDtos.SpendRequest request) {
        return ApiResponse.ok(service.spend(storeId, id, request));
    }
}
