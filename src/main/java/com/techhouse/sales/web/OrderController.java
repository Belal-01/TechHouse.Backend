package com.techhouse.sales.web;

import com.techhouse.identity.config.CurrentStore;
import com.techhouse.sales.application.OrderService;
import com.techhouse.sales.domain.OrderStatus;
import com.techhouse.sales.web.dto.DeliverRequest;
import com.techhouse.sales.web.dto.OrderDetail;
import com.techhouse.sales.web.dto.OrderRequest;
import com.techhouse.sales.web.dto.OrderSummary;
import com.techhouse.sales.web.dto.StatusChangeRequest;
import com.techhouse.shared.web.ApiResponse;
import com.techhouse.shared.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/sales/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService service;

    @GetMapping
    public ApiResponse<PageResponse<OrderSummary>> list(@CurrentStore UUID storeId,
                                                        @RequestParam(required = false) OrderStatus status,
                                                        @RequestParam(required = false) String q,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(storeId, status, q, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderDetail> get(@CurrentStore UUID storeId, @PathVariable UUID id) {
        return ApiResponse.ok(service.get(storeId, id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<OrderDetail>> create(@CurrentStore UUID storeId,
                                                           @Valid @RequestBody OrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(storeId, request)));
    }

    @PostMapping("/{id}/deliver")
    public ApiResponse<OrderDetail> deliver(@CurrentStore UUID storeId, @PathVariable UUID id,
                                            @Valid @RequestBody DeliverRequest body) {
        return ApiResponse.ok(service.deliver(storeId, id, body.accountId(), body.note()));
    }

    @PostMapping("/{id}/return")
    public ApiResponse<OrderDetail> markReturned(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                 @Valid @RequestBody(required = false) StatusChangeRequest body) {
        return ApiResponse.ok(service.markReturned(storeId, id, note(body)));
    }

    @PostMapping("/{id}/void")
    public ApiResponse<OrderDetail> voidOrder(@CurrentStore UUID storeId, @PathVariable UUID id,
                                              @Valid @RequestBody(required = false) StatusChangeRequest body) {
        return ApiResponse.ok(service.voidOrder(storeId, id, note(body)));
    }

    private static String note(StatusChangeRequest body) {
        return body == null ? null : body.note();
    }
}
