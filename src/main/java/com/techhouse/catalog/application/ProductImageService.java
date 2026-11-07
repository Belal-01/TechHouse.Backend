package com.techhouse.catalog.application;

import com.techhouse.catalog.domain.ProductImage;
import com.techhouse.catalog.persistence.ProductImageMeta;
import com.techhouse.catalog.persistence.ProductImageRepository;
import com.techhouse.catalog.persistence.ProductRepository;
import com.techhouse.catalog.web.dto.ImageResponse;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductImageService {

    static final int MAX_IMAGES_PER_PRODUCT = 10;
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;

    private final ProductImageRepository imageRepository;
    private final ProductRepository productRepository;

    @Transactional
    public ImageResponse upload(UUID storeId, UUID productId, MultipartFile file) {
        requireProduct(storeId, productId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("IMAGE_EMPTY", "The file is empty");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new BusinessException(HttpStatus.CONTENT_TOO_LARGE, "IMAGE_TOO_LARGE", "Image is larger than 5 MB");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("IMAGE_EMPTY", "The file could not be read", e);
        }
        // Trust the bytes, not the client-supplied content type.
        String contentType = detectType(data);
        if (contentType == null) {
            throw new BusinessException("IMAGE_TYPE_NOT_ALLOWED", "Only JPEG, PNG and WebP images are allowed");
        }

        List<ProductImageMeta> existing = imageRepository.findByProductIdOrderByPositionAsc(productId);
        if (existing.size() >= MAX_IMAGES_PER_PRODUCT) {
            throw new BusinessException(HttpStatus.CONFLICT, "IMAGE_LIMIT_REACHED",
                    "A product can have at most " + MAX_IMAGES_PER_PRODUCT + " images");
        }
        int position = existing.stream().mapToInt(ProductImageMeta::getPosition).max().orElse(-1) + 1;

        ProductImage image = new ProductImage();
        image.setProductId(productId);
        image.setPosition(position);
        image.setContentType(contentType);
        image.setSizeBytes(data.length);
        image.setData(data);
        image = imageRepository.saveAndFlush(image);
        return new ImageResponse(image.getId(), image.getPosition(), image.getContentType(), image.getSizeBytes());
    }

    @Transactional
    public void delete(UUID storeId, UUID productId, UUID imageId) {
        requireProduct(storeId, productId);
        if (imageRepository.deleteImage(imageId, productId) == 0) {
            throw new ResourceNotFoundException("IMAGE_NOT_FOUND", "Image", imageId);
        }
        // Variants pointing at it are cleared by the database (ON DELETE SET NULL); close the gap in positions.
        int position = 0;
        for (ProductImageMeta image : imageRepository.findByProductIdOrderByPositionAsc(productId)) {
            imageRepository.setPosition(image.getId(), position++);
        }
    }

    @Transactional
    public List<ImageResponse> reorder(UUID storeId, UUID productId, List<UUID> imageIds) {
        requireProduct(storeId, productId);
        Set<UUID> current = new HashSet<>();
        imageRepository.findByProductIdOrderByPositionAsc(productId).forEach(m -> current.add(m.getId()));
        if (imageIds.size() != current.size() || !current.equals(new HashSet<>(imageIds))) {
            throw new BusinessException("IMAGE_ORDER_INVALID", "The order must list every image of the product once");
        }
        for (int position = 0; position < imageIds.size(); position++) {
            imageRepository.setPosition(imageIds.get(position), position);
        }
        return imageRepository.findByProductIdOrderByPositionAsc(productId).stream()
                .map(m -> new ImageResponse(m.getId(), m.getPosition(), m.getContentType(), m.getSizeBytes()))
                .toList();
    }

    /** The image only if its product belongs to the given store. */
    @Transactional(readOnly = true)
    public ProductImage getForStore(UUID storeId, UUID imageId) {
        ProductImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("IMAGE_NOT_FOUND", "Image", imageId));
        if (!productRepository.existsByIdAndStoreId(image.getProductId(), storeId)) {
            throw new ResourceNotFoundException("IMAGE_NOT_FOUND", "Image", imageId);
        }
        return image;
    }

    private void requireProduct(UUID storeId, UUID productId) {
        if (productRepository.findByIdAndStoreIdAndDeletedAtIsNull(productId, storeId).isEmpty()) {
            throw new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product", productId);
        }
    }

    static String detectType(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 12
                && "RIFF".equals(new String(b, 0, 4, StandardCharsets.US_ASCII))
                && "WEBP".equals(new String(b, 8, 4, StandardCharsets.US_ASCII))) {
            return "image/webp";
        }
        return null;
    }
}
