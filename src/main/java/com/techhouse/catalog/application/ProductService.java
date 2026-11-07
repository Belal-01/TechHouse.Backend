package com.techhouse.catalog.application;

import com.techhouse.catalog.domain.Product;
import com.techhouse.catalog.persistence.CategoryRepository;
import com.techhouse.catalog.persistence.ProductImageRepository;
import com.techhouse.catalog.persistence.ProductRepository;
import com.techhouse.catalog.persistence.ProductSpecifications;
import com.techhouse.catalog.web.dto.CategorySummary;
import com.techhouse.catalog.web.dto.ImageResponse;
import com.techhouse.catalog.web.dto.ProductDetailResponse;
import com.techhouse.catalog.web.dto.ProductRequest;
import com.techhouse.catalog.web.dto.ProductSummaryResponse;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import com.techhouse.shared.web.PageResponse;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORTABLE = Set.of("name", "updatedAt", "createdAt", "sellingPrice");
    /** Stand-in "other product" id when checking uniqueness for a product that doesn't exist yet. */
    private static final UUID NO_PRODUCT = new UUID(0L, 0L);

    private final ProductRepository productRepository;
    private final ProductImageRepository imageRepository;
    private final CategoryRepository categoryRepository;
    private final EntityManager em;

    // ---- Queries ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryResponse> list(UUID storeId, String search, UUID categoryId, Boolean active,
                                                     int page, int size, String sort) {
        boolean descending = sort != null && sort.startsWith("-");
        String property = sort == null ? "name" : sort.replaceFirst("^-", "");
        if (!SORTABLE.contains(property)) {
            property = "name";
        }
        Sort order = (descending ? Sort.by(property).descending() : Sort.by(property)).and(Sort.by("id"));
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), order);

        Page<Product> result = productRepository.findAll(
                ProductSpecifications.catalog(storeId, search, categoryId, active), pageable);

        // Batch the related lookups so a page costs a fixed number of queries.
        Set<UUID> productIds = result.getContent().stream().map(Product::getId).collect(Collectors.toSet());
        Map<UUID, UUID> primaryImage = new HashMap<>();
        if (!productIds.isEmpty()) {
            imageRepository.findByProductIdInOrderByPositionAsc(productIds)
                    .forEach(m -> primaryImage.putIfAbsent(m.getProductId(), m.getId()));
        }
        Set<UUID> categoryIds = result.getContent().stream().map(Product::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, CategorySummary> categories = categoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(c -> c.getId(), c -> new CategorySummary(c.getId(), c.getName())));

        return PageResponse.from(result.map(p -> new ProductSummaryResponse(
                p.getId(), p.getName(), categories.get(p.getCategoryId()), p.isActive(), p.getSellingPrice(),
                primaryImage.get(p.getId()), p.getSku(), p.getUpdatedAt())));
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse get(UUID storeId, UUID id) {
        return toDetail(find(storeId, id));
    }

    // ---- Commands --------------------------------------------------------------------

    @Transactional
    public ProductDetailResponse create(UUID storeId, ProductRequest request) {
        Product product = new Product();
        product.setStoreId(storeId);
        apply(product, storeId, request);
        em.persist(product);
        em.flush();
        return toDetail(product);
    }

    @Transactional
    public ProductDetailResponse update(UUID storeId, UUID id, ProductRequest request) {
        Product product = find(storeId, id);
        if (request.version() != null && request.version() != product.getVersion()) {
            throw new BusinessException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                    "The product was modified by someone else");
        }
        apply(product, storeId, request);
        em.flush();
        return toDetail(product);
    }

    @Transactional
    public void delete(UUID storeId, UUID id) {
        Product product = find(storeId, id);
        product.setDeletedAt(Instant.now());
        em.flush();
    }

    // ---- Internals -------------------------------------------------------------------

    private Product find(UUID storeId, UUID id) {
        return productRepository.findByIdAndStoreIdAndDeletedAtIsNull(id, storeId)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product", id));
    }

    /** Validates the request and copies it onto the product. */
    private void apply(Product product, UUID storeId, ProductRequest req) {
        if (req.categoryId() != null && categoryRepository.findByIdAndStoreId(req.categoryId(), storeId).isEmpty()) {
            throw BusinessException.onField(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "categoryId", "Category not found");
        }

        UUID self = product.getId() == null ? NO_PRODUCT : product.getId();
        String sku = blankToNull(req.sku());
        if (sku != null && productRepository.existsByStoreIdAndSkuAndDeletedAtIsNullAndIdNot(storeId, sku, self)) {
            throw BusinessException.onField(HttpStatus.CONFLICT, "PRODUCT_SKU_EXISTS", "sku",
                    "SKU already used by another product");
        }
        String barcode = blankToNull(req.barcode());
        if (barcode != null && productRepository.existsByStoreIdAndBarcodeAndDeletedAtIsNullAndIdNot(storeId, barcode, self)) {
            throw BusinessException.onField(HttpStatus.CONFLICT, "PRODUCT_BARCODE_EXISTS", "barcode",
                    "Barcode already used by another product");
        }

        product.setName(req.name().trim());
        product.setDescription(blankToNull(req.description()));
        product.setCategoryId(req.categoryId());
        product.setSku(sku);
        product.setBarcode(barcode);
        product.setSellingPrice(req.sellingPrice().setScale(4, RoundingMode.HALF_UP));
        product.setWarrantyMonths(req.warrantyMonths());
        product.setSpecifications(normalizeSpecifications(req.specifications()));
        product.setActive(req.active());
    }

    private static Map<String, String> normalizeSpecifications(Map<String, String> raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((key, value) -> {
                if (key != null && !key.isBlank()) {
                    result.put(key.trim(), value == null ? "" : value.trim());
                }
            });
        }
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ProductDetailResponse toDetail(Product p) {
        var images = imageRepository.findByProductIdOrderByPositionAsc(p.getId()).stream()
                .map(m -> new ImageResponse(m.getId(), m.getPosition(), m.getContentType(), m.getSizeBytes()))
                .toList();
        CategorySummary category = p.getCategoryId() == null ? null
                : categoryRepository.findById(p.getCategoryId())
                        .map(c -> new CategorySummary(c.getId(), c.getName())).orElse(null);
        return new ProductDetailResponse(p.getId(), p.getName(), p.getDescription(), category, p.getSku(),
                p.getBarcode(), p.getSellingPrice(), p.getWarrantyMonths(), p.getSpecifications(), p.isActive(),
                images, p.getVersion(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
