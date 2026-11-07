package com.techhouse.catalog.web;

import com.techhouse.catalog.application.CategoryService;
import com.techhouse.catalog.web.dto.CategoryRequest;
import com.techhouse.catalog.web.dto.CategoryResponse;
import com.techhouse.identity.config.CurrentStore;
import com.techhouse.shared.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/catalog/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public ApiResponse<List<CategoryResponse>> list(@CurrentStore UUID storeId) {
        return ApiResponse.ok(categoryService.list(storeId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CategoryResponse>> create(@CurrentStore UUID storeId,
                                                                 @Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(categoryService.create(storeId, request)));
    }

    @PutMapping("/{id}")
    public ApiResponse<CategoryResponse> update(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                @Valid @RequestBody CategoryRequest request) {
        return ApiResponse.ok(categoryService.update(storeId, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentStore UUID storeId, @PathVariable UUID id) {
        categoryService.delete(storeId, id);
        return ResponseEntity.noContent().build();
    }
}
