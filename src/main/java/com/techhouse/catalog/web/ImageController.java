package com.techhouse.catalog.web;

import com.techhouse.catalog.application.ProductImageService;
import com.techhouse.catalog.domain.ProductImage;
import com.techhouse.identity.config.CurrentStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/** Serves image bytes. Image ids never change content, so browsers may cache them (privately). */
@RestController
@RequestMapping("/api/v1/admin/catalog/images")
@RequiredArgsConstructor
public class ImageController {

    private final ProductImageService imageService;

    @GetMapping("/{imageId}")
    public ResponseEntity<byte[]> download(@CurrentStore UUID storeId, @PathVariable UUID imageId) {
        ProductImage image = imageService.getForStore(storeId, imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(image.getData());
    }
}
