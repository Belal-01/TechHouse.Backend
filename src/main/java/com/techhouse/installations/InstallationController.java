package com.techhouse.installations;

import com.techhouse.identity.config.CurrentStore;
import com.techhouse.shared.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/installations")
@RequiredArgsConstructor
public class InstallationController {
    private final InstallationService service;

    @GetMapping
    public ApiResponse<InstallationService.Overview> overview(@CurrentStore UUID storeId) {
        return ApiResponse.ok(service.overview(storeId));
    }

    @PostMapping("/materials")
    public ApiResponse<Void> purchase(@CurrentStore UUID storeId, @Valid @RequestBody InstallationService.MaterialInput input) {
        service.purchase(storeId, input);
        return ApiResponse.ok(null);
    }

    @PutMapping("/materials/{id}/depleted")
    public ApiResponse<Void> markDepleted(@CurrentStore UUID storeId, @PathVariable UUID id) {
        service.markDepleted(storeId, id);
        return ApiResponse.ok(null);
    }

    @PostMapping
    public ApiResponse<Void> record(@CurrentStore UUID storeId, @Valid @RequestBody InstallationService.InstallationInput input) {
        service.record(storeId, input);
        return ApiResponse.ok(null);
    }
}
