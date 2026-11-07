package com.techhouse.catalog.application;

import com.techhouse.catalog.domain.Category;
import com.techhouse.catalog.persistence.CategoryRepository;
import com.techhouse.catalog.persistence.ProductRepository;
import com.techhouse.catalog.web.dto.CategoryRequest;
import com.techhouse.catalog.web.dto.CategoryResponse;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<CategoryResponse> list(UUID storeId) {
        return categoryRepository.findByStoreIdOrderByNameAsc(storeId).stream()
                .map(CategoryService::toResponse)
                .toList();
    }

    @Transactional
    public CategoryResponse create(UUID storeId, CategoryRequest request) {
        String name = request.name().trim();
        if (categoryRepository.existsByStoreIdAndNameIgnoreCase(storeId, name)) {
            throw nameExists(name);
        }
        Category category = new Category();
        category.setStoreId(storeId);
        category.setName(name);
        // save() may return a merged copy (BaseEntity.version starts at 0), so use its result
        return toResponse(categoryRepository.saveAndFlush(category));
    }

    @Transactional
    public CategoryResponse update(UUID storeId, UUID id, CategoryRequest request) {
        Category category = find(storeId, id);
        String name = request.name().trim();
        if (categoryRepository.existsByStoreIdAndNameIgnoreCaseAndIdNot(storeId, name, id)) {
            throw nameExists(name);
        }
        category.setName(name);
        return toResponse(categoryRepository.saveAndFlush(category));
    }

    @Transactional
    public void delete(UUID storeId, UUID id) {
        Category category = find(storeId, id);
        if (productRepository.countByCategoryIdAndDeletedAtIsNull(id) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "CATEGORY_IN_USE", "Category still has products");
        }
        productRepository.clearCategory(id);
        categoryRepository.delete(category);
    }

    private Category find(UUID storeId, UUID id) {
        return categoryRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new ResourceNotFoundException("CATEGORY_NOT_FOUND", "Category", id));
    }

    private static BusinessException nameExists(String name) {
        return BusinessException.onField(HttpStatus.CONFLICT, "CATEGORY_NAME_EXISTS", "name",
                "Category name already exists: " + name);
    }

    private static CategoryResponse toResponse(Category c) {
        return new CategoryResponse(c.getId(), c.getName(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
