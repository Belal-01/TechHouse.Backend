package com.techhouse.catalog.application;

import com.techhouse.catalog.domain.Product;
import com.techhouse.catalog.domain.ProductVariant;
import com.techhouse.catalog.persistence.CategoryRepository;
import com.techhouse.catalog.persistence.ProductImageMeta;
import com.techhouse.catalog.persistence.ProductImageRepository;
import com.techhouse.catalog.persistence.ProductRepository;
import com.techhouse.catalog.persistence.ProductSpecifications;
import com.techhouse.catalog.persistence.ProductVariantRepository;
import com.techhouse.catalog.web.dto.CategorySummary;
import com.techhouse.catalog.web.dto.ImageResponse;
import com.techhouse.catalog.web.dto.ProductDetailResponse;
import com.techhouse.catalog.web.dto.ProductRequest;
import com.techhouse.catalog.web.dto.ProductSummaryResponse;
import com.techhouse.catalog.web.dto.VariantRequest;
import com.techhouse.catalog.web.dto.VariantResponse;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import com.techhouse.shared.web.PageResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORTABLE = Set.of("name", "updatedAt", "createdAt");
    /** Stand-in "other product" id when checking uniqueness for a product that doesn't exist yet. */
    private static final UUID NO_PRODUCT = new UUID(0L, 0L);

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
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
        Map<UUID, List<ProductVariant>> variantsByProduct = productIds.isEmpty() ? Map.of()
                : variantRepository.findByProductIds(productIds).stream()
                        .collect(Collectors.groupingBy(v -> v.getProduct().getId()));
        Map<UUID, UUID> primaryImage = new HashMap<>();
        if (!productIds.isEmpty()) {
            imageRepository.findByProductIdInOrderByPositionAsc(productIds)
                    .forEach(m -> primaryImage.putIfAbsent(m.getProductId(), m.getId()));
        }
        Set<UUID> categoryIds = result.getContent().stream().map(Product::getCategoryId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, CategorySummary> categories = categoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(c -> c.getId(), c -> new CategorySummary(c.getId(), c.getName())));

        return PageResponse.from(result.map(p -> {
            List<ProductVariant> variants = variantsByProduct.getOrDefault(p.getId(), List.of());
            BigDecimal min = variants.stream().map(ProductVariant::getSellingPrice).min(Comparator.naturalOrder()).orElse(null);
            BigDecimal max = variants.stream().map(ProductVariant::getSellingPrice).max(Comparator.naturalOrder()).orElse(null);
            String sku = variants.size() == 1 ? variants.get(0).getSku() : null;
            return new ProductSummaryResponse(p.getId(), p.getName(), categories.get(p.getCategoryId()), p.isActive(),
                    variants.size(), min, max, primaryImage.get(p.getId()), sku, p.getUpdatedAt());
        }));
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
        apply(product, storeId, request, Set.of());
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
        Set<UUID> imageIds = imageRepository.findByProductIdOrderByPositionAsc(id).stream()
                .map(ProductImageMeta::getId).collect(Collectors.toSet());
        apply(product, storeId, request, imageIds);
        // Bump the version even when only variants changed, so concurrent editors are detected.
        em.lock(product, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
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

    /** Validates the request and copies it onto the product, syncing its variants. */
    private void apply(Product product, UUID storeId, ProductRequest req, Set<UUID> imageIds) {
        if (req.categoryId() != null && categoryRepository.findByIdAndStoreId(req.categoryId(), storeId).isEmpty()) {
            throw BusinessException.onField(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "categoryId", "Category not found");
        }

        List<String> optionNames = req.optionNames() == null ? List.of()
                : req.optionNames().stream().map(String::trim).toList();
        Set<String> seenOptions = new HashSet<>();
        for (int i = 0; i < optionNames.size(); i++) {
            if (!seenOptions.add(optionNames.get(i).toLowerCase(Locale.ROOT))) {
                throw BusinessException.onField(HttpStatus.BAD_REQUEST, "PRODUCT_OPTION_DUPLICATE",
                        "optionNames[" + i + "]", "Duplicate option name");
            }
        }

        List<VariantRequest> variants = req.variants();
        if (optionNames.isEmpty() && variants.size() != 1) {
            throw BusinessException.onField(HttpStatus.BAD_REQUEST, "PRODUCT_OPTIONS_REQUIRED",
                    "optionNames", "A product with several variants needs at least one option");
        }

        UUID excludeProductId = product.getId() == null ? NO_PRODUCT : product.getId();
        Map<UUID, ProductVariant> existing = product.getVariants().stream()
                .collect(Collectors.toMap(ProductVariant::getId, v -> v));
        Set<UUID> keep = new HashSet<>();
        Set<String> combinations = new HashSet<>();
        Set<String> skus = new HashSet<>();
        Set<String> barcodes = new HashSet<>();
        List<ProductVariant> ordered = new ArrayList<>();

        for (int i = 0; i < variants.size(); i++) {
            VariantRequest r = variants.get(i);
            String at = "variants[" + i + "]";

            List<String> values = r.optionValues() == null ? List.of()
                    : r.optionValues().stream().map(v -> v == null ? "" : v.trim()).toList();
            if (values.size() != optionNames.size() || values.stream().anyMatch(String::isEmpty)) {
                throw BusinessException.onField(HttpStatus.BAD_REQUEST, "VARIANT_OPTIONS_MISMATCH",
                        at + ".optionValues", "Provide one value per option");
            }
            if (!combinations.add(String.join("\u0000", values).toLowerCase(Locale.ROOT))) {
                throw BusinessException.onField(HttpStatus.BAD_REQUEST, "VARIANT_COMBINATION_DUPLICATE",
                        at + ".optionValues", "Duplicate option combination");
            }

            String sku = blankToNull(r.sku());
            if (sku != null) {
                if (!skus.add(sku)) {
                    throw BusinessException.onField(HttpStatus.BAD_REQUEST, "VARIANT_SKU_DUPLICATE", at + ".sku",
                            "Duplicate SKU within the product");
                }
                if (variantRepository.skuUsedByOtherProduct(storeId, sku, excludeProductId)) {
                    throw BusinessException.onField(HttpStatus.CONFLICT, "VARIANT_SKU_EXISTS", at + ".sku",
                            "SKU already used by another product");
                }
            }
            String barcode = blankToNull(r.barcode());
            if (barcode != null) {
                if (!barcodes.add(barcode)) {
                    throw BusinessException.onField(HttpStatus.BAD_REQUEST, "VARIANT_BARCODE_DUPLICATE", at + ".barcode",
                            "Duplicate barcode within the product");
                }
                if (variantRepository.barcodeUsedByOtherProduct(storeId, barcode, excludeProductId)) {
                    throw BusinessException.onField(HttpStatus.CONFLICT, "VARIANT_BARCODE_EXISTS", at + ".barcode",
                            "Barcode already used by another product");
                }
            }
            if (r.imageId() != null && !imageIds.contains(r.imageId())) {
                throw BusinessException.onField(HttpStatus.BAD_REQUEST, "IMAGE_NOT_FOUND", at + ".imageId",
                        "Image does not belong to this product");
            }

            ProductVariant variant;
            if (r.id() != null) {
                variant = existing.get(r.id());
                if (variant == null) {
                    throw BusinessException.onField(HttpStatus.NOT_FOUND, "VARIANT_NOT_FOUND", at, "Variant not found");
                }
                if (!keep.add(r.id())) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Variant listed twice");
                }
            } else {
                variant = new ProductVariant();
                variant.setProduct(product);
                variant.setStoreId(storeId);
            }
            variant.setPosition(i);
            variant.setOptionValues(new ArrayList<>(values));
            variant.setSku(sku);
            variant.setBarcode(barcode);
            variant.setSellingPrice(r.sellingPrice().setScale(4, RoundingMode.HALF_UP));
            variant.setImageId(r.imageId());
            variant.setActive(r.active());
            ordered.add(variant);
        }

        product.setName(req.name().trim());
        product.setDescription(blankToNull(req.description()));
        product.setCategoryId(req.categoryId());
        product.setWarrantyMonths(req.warrantyMonths());
        product.setSpecifications(normalizeSpecifications(req.specifications()));
        product.setOptionNames(new ArrayList<>(optionNames));
        product.setActive(req.active());

        // Variants missing from the request are removed (orphanRemoval); new ones are added.
        product.getVariants().removeIf(v -> v.getId() != null && !keep.contains(v.getId()));
        for (ProductVariant v : ordered) {
            if (v.getId() == null) {
                product.getVariants().add(v);
            }
        }
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
        List<VariantResponse> variants = p.getVariants().stream()
                .sorted(Comparator.comparingInt(ProductVariant::getPosition))
                .map(v -> new VariantResponse(v.getId(), v.getOptionValues(), v.getSku(), v.getBarcode(),
                        v.getSellingPrice(), v.getImageId(), v.isActive()))
                .toList();
        List<ImageResponse> images = imageRepository.findByProductIdOrderByPositionAsc(p.getId()).stream()
                .map(m -> new ImageResponse(m.getId(), m.getPosition(), m.getContentType(), m.getSizeBytes()))
                .toList();
        CategorySummary category = p.getCategoryId() == null ? null
                : categoryRepository.findById(p.getCategoryId())
                        .map(c -> new CategorySummary(c.getId(), c.getName())).orElse(null);
        return new ProductDetailResponse(p.getId(), p.getName(), p.getDescription(), category, p.getWarrantyMonths(),
                p.getSpecifications(), p.getOptionNames(), p.isActive(), variants, images, p.getVersion(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
