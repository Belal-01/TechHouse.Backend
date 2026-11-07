package com.techhouse.catalog.web;

import com.techhouse.catalog.application.ProductImageService;
import com.techhouse.catalog.application.ProductService;
import com.techhouse.catalog.web.dto.ImageOrderRequest;
import com.techhouse.catalog.web.dto.ImageResponse;
import com.techhouse.catalog.web.dto.ProductDetailResponse;
import com.techhouse.catalog.web.dto.ProductRequest;
import com.techhouse.catalog.web.dto.ProductSummaryResponse;
import com.techhouse.identity.config.CurrentStore;
import com.techhouse.shared.web.ApiResponse;
import com.techhouse.shared.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/catalog/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ProductImageService imageService;

    @GetMapping
    public ApiResponse<PageResponse<ProductSummaryResponse>> list(
            @CurrentStore UUID storeId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") String sort) {
        return ApiResponse.ok(productService.list(storeId, search, categoryId, active, page, size, sort));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductDetailResponse> get(@CurrentStore UUID storeId, @PathVariable UUID id) {
        return ApiResponse.ok(productService.get(storeId, id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductDetailResponse>> create(@CurrentStore UUID storeId,
                                                                      @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(productService.create(storeId, request)));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProductDetailResponse> update(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                     @Valid @RequestBody ProductRequest request) {
        return ApiResponse.ok(productService.update(storeId, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentStore UUID storeId, @PathVariable UUID id) {
        productService.delete(storeId, id);
        return ResponseEntity.noContent().build();
    }

    // ---- Images ----------------------------------------------------------------------

    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ImageResponse>> uploadImage(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                                   @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(imageService.upload(storeId, id, file)));
    }

    @DeleteMapping("/{id}/images/{imageId}")
    public ResponseEntity<Void> deleteImage(@CurrentStore UUID storeId, @PathVariable UUID id,
                                            @PathVariable UUID imageId) {
        imageService.delete(storeId, id, imageId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/images/order")
    public ApiResponse<List<ImageResponse>> reorderImages(@CurrentStore UUID storeId, @PathVariable UUID id,
                                                          @Valid @RequestBody ImageOrderRequest request) {
        return ApiResponse.ok(imageService.reorder(storeId, id, request.imageIds()));
    }
}
