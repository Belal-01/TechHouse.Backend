package com.techhouse.finance.application;

import com.techhouse.finance.domain.ExpenseCategory;
import com.techhouse.finance.persistence.ExpenseCategoryRepository;
import com.techhouse.finance.web.dto.ExpenseDtos;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExpenseCategoryService {

    private final ExpenseCategoryRepository repository;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<ExpenseDtos.CategoryResponse> list(UUID storeId) {
        return repository.findByStoreIdOrderByNameAsc(storeId).stream().map(ExpenseCategoryService::toResponse).toList();
    }

    @Transactional
    public ExpenseDtos.CategoryResponse create(UUID storeId, ExpenseDtos.CategoryRequest request) {
        String name = request.name().trim();
        if (repository.existsByStoreIdAndNameIgnoreCase(storeId, name)) throw nameExists();
        ExpenseCategory c = new ExpenseCategory();
        c.setStoreId(storeId);
        c.setName(name);
        if (request.active() != null) c.setActive(request.active());
        return toResponse(repository.saveAndFlush(c));
    }

    @Transactional
    public ExpenseDtos.CategoryResponse update(UUID storeId, UUID id, ExpenseDtos.CategoryRequest request) {
        ExpenseCategory c = find(storeId, id);
        if (request.version() == null || !request.version().equals(c.getVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "The record was modified by someone else");
        }
        String name = request.name().trim();
        if (repository.existsByStoreIdAndNameIgnoreCaseAndIdNot(storeId, name, id)) throw nameExists();
        if (Boolean.FALSE.equals(request.active()) && c.getSystemKey() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "EXPENSE_CATEGORY_SYSTEM", "This category is used by the app");
        }
        c.setName(name);
        if (request.active() != null) c.setActive(request.active());
        return toResponse(repository.saveAndFlush(c));
    }

    @Transactional
    public void delete(UUID storeId, UUID id) {
        ExpenseCategory c = find(storeId, id);
        if (c.getSystemKey() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "EXPENSE_CATEGORY_SYSTEM", "This category is used by the app");
        }
        Long used = jdbc.queryForObject("select count(*) from expenses where category_id = ?", Long.class, id);
        if (used != null && used > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "EXPENSE_CATEGORY_IN_USE", "Category has expenses; deactivate it instead");
        }
        repository.delete(c);
    }

    private ExpenseCategory find(UUID storeId, UUID id) {
        return repository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new ResourceNotFoundException("EXPENSE_CATEGORY_NOT_FOUND", "Expense category", id));
    }

    private static BusinessException nameExists() {
        return BusinessException.onField(HttpStatus.CONFLICT, "EXPENSE_CATEGORY_NAME_EXISTS", "name", "Category already exists");
    }

    static ExpenseDtos.CategoryResponse toResponse(ExpenseCategory c) {
        return new ExpenseDtos.CategoryResponse(c.getId(), c.getName(), c.getSystemKey() != null, c.isActive(), c.getVersion());
    }
}
