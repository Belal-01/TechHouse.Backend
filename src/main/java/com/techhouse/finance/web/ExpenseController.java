package com.techhouse.finance.web;

import com.techhouse.finance.application.ExpenseCategoryService;
import com.techhouse.finance.application.ExpenseService;
import com.techhouse.finance.web.dto.ExpenseDtos;
import com.techhouse.identity.config.CurrentStore;
import com.techhouse.shared.web.ApiResponse;
import com.techhouse.shared.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/finance")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenses;
    private final ExpenseCategoryService categories;

    @GetMapping("/expenses")
    public ApiResponse<PageResponse<ExpenseDtos.ExpenseResponse>> list(
            @CurrentStore UUID storeId, @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(expenses.list(storeId, categoryId, from, to, page, size));
    }

    @GetMapping("/expenses/summary")
    public ApiResponse<ExpenseDtos.Summary> summary(
            @CurrentStore UUID storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(expenses.summary(storeId, from, to));
    }

    @PostMapping("/expenses")
    public ResponseEntity<ApiResponse<ExpenseDtos.ExpenseResponse>> create(@CurrentStore UUID storeId,
                                                                           @Valid @RequestBody ExpenseDtos.ExpenseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(expenses.create(storeId, request)));
    }

    @PostMapping("/expenses/{id}/void")
    public ApiResponse<ExpenseDtos.ExpenseResponse> voidExpense(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                                @Valid @RequestBody(required = false) ExpenseDtos.VoidRequest body) {
        return ApiResponse.ok(expenses.voidExpense(storeId, id, body == null ? null : body.note()));
    }

    @GetMapping("/expense-categories")
    public ApiResponse<List<ExpenseDtos.CategoryResponse>> categories(@CurrentStore UUID storeId) {
        return ApiResponse.ok(categories.list(storeId));
    }

    @PostMapping("/expense-categories")
    public ResponseEntity<ApiResponse<ExpenseDtos.CategoryResponse>> createCategory(
            @CurrentStore UUID storeId, @Valid @RequestBody ExpenseDtos.CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(categories.create(storeId, request)));
    }

    @PutMapping("/expense-categories/{id}")
    public ApiResponse<ExpenseDtos.CategoryResponse> updateCategory(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                                    @Valid @RequestBody ExpenseDtos.CategoryRequest request) {
        return ApiResponse.ok(categories.update(storeId, id, request));
    }

    @DeleteMapping("/expense-categories/{id}")
    public ResponseEntity<Void> deleteCategory(@CurrentStore UUID storeId, @PathVariable UUID id) {
        categories.delete(storeId, id);
        return ResponseEntity.noContent().build();
    }
}
