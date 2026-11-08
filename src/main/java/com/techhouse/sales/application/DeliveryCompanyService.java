package com.techhouse.sales.application;

import com.techhouse.sales.domain.DeliveryCompany;
import com.techhouse.sales.persistence.DeliveryCompanyRepository;
import com.techhouse.sales.persistence.OrderRepository;
import com.techhouse.sales.web.dto.DeliveryCompanyRequest;
import com.techhouse.sales.web.dto.DeliveryCompanyResponse;
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
public class DeliveryCompanyService {

    private final DeliveryCompanyRepository repository;
    private final OrderRepository orders;

    @Transactional(readOnly = true)
    public List<DeliveryCompanyResponse> list(UUID storeId) {
        return repository.findByStoreIdOrderByNameAsc(storeId).stream().map(DeliveryCompanyService::toResponse).toList();
    }

    @Transactional
    public DeliveryCompanyResponse create(UUID storeId, DeliveryCompanyRequest request) {
        String name = request.name().trim();
        if (repository.existsByStoreIdAndNameIgnoreCase(storeId, name)) throw nameExists(name);
        DeliveryCompany company = new DeliveryCompany();
        company.setStoreId(storeId);
        apply(company, request, name);
        return toResponse(repository.saveAndFlush(company));
    }

    @Transactional
    public DeliveryCompanyResponse update(UUID storeId, UUID id, DeliveryCompanyRequest request) {
        DeliveryCompany company = find(storeId, id);
        if (request.version() == null || !request.version().equals(company.getVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                    "The record was modified by someone else");
        }
        String name = request.name().trim();
        if (repository.existsByStoreIdAndNameIgnoreCaseAndIdNot(storeId, name, id)) throw nameExists(name);
        apply(company, request, name);
        return toResponse(repository.saveAndFlush(company));
    }

    @Transactional
    public void delete(UUID storeId, UUID id) {
        DeliveryCompany company = find(storeId, id);
        if (orders.existsByDeliveryCompanyId(id)) {
            throw new BusinessException(HttpStatus.CONFLICT, "DELIVERY_COMPANY_IN_USE",
                    "The company has orders; deactivate it instead");
        }
        repository.delete(company);
    }

    DeliveryCompany find(UUID storeId, UUID id) {
        return repository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new ResourceNotFoundException("DELIVERY_COMPANY_NOT_FOUND", "Delivery company", id));
    }

    private static void apply(DeliveryCompany c, DeliveryCompanyRequest r, String name) {
        c.setName(name);
        c.setContactInfo(r.contactInfo() == null || r.contactInfo().isBlank() ? null : r.contactInfo().trim());
        if (r.active() != null) c.setActive(r.active());
    }

    private static BusinessException nameExists(String name) {
        return BusinessException.onField(HttpStatus.CONFLICT, "DELIVERY_COMPANY_NAME_EXISTS", "name",
                "Delivery company already exists: " + name);
    }

    static DeliveryCompanyResponse toResponse(DeliveryCompany c) {
        return new DeliveryCompanyResponse(c.getId(), c.getName(), c.getContactInfo(), c.isActive(), c.getVersion(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
