package com.techhouse.procurement.web;

import com.techhouse.identity.config.CurrentStore;
import com.techhouse.procurement.application.PurchaseInvoiceFileService;
import com.techhouse.procurement.application.PurchaseInvoiceService;
import com.techhouse.procurement.domain.PurchaseInvoiceFile;
import com.techhouse.procurement.web.dto.PurchaseInvoiceRequest;
import com.techhouse.procurement.web.dto.PurchaseInvoiceResponse;
import com.techhouse.procurement.web.dto.PurchaseInvoiceSummary;
import com.techhouse.shared.web.ApiResponse;
import com.techhouse.shared.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/procurement/invoices")
@RequiredArgsConstructor
public class PurchaseInvoiceController {

    private final PurchaseInvoiceService invoiceService;
    private final PurchaseInvoiceFileService fileService;

    @GetMapping
    public ApiResponse<PageResponse<PurchaseInvoiceSummary>> list(@CurrentStore UUID storeId,
                                                                  @RequestParam(required = false) UUID supplierId,
                                                                  @RequestParam(defaultValue = "0") int page,
                                                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(invoiceService.list(storeId, supplierId, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<PurchaseInvoiceResponse> get(@CurrentStore UUID storeId, @PathVariable UUID id) {
        return ApiResponse.ok(invoiceService.get(storeId, id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PurchaseInvoiceResponse>> create(@CurrentStore UUID storeId,
                                                                       @Valid @RequestBody PurchaseInvoiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(invoiceService.create(storeId, request)));
    }

    @PostMapping(value = "/{id}/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> uploadAttachment(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                 @RequestParam("file") MultipartFile file) {
        fileService.upload(storeId, id, file);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/attachment")
    public ResponseEntity<byte[]> downloadAttachment(@CurrentStore UUID storeId, @PathVariable UUID id) {
        PurchaseInvoiceFile file = fileService.get(storeId, id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.getFileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.getData());
    }

    @DeleteMapping("/{id}/attachment")
    public ResponseEntity<Void> deleteAttachment(@CurrentStore UUID storeId, @PathVariable UUID id) {
        fileService.delete(storeId, id);
        return ResponseEntity.noContent().build();
    }
}
