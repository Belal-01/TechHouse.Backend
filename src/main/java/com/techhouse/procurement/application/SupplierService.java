package com.techhouse.procurement.application;

import com.techhouse.procurement.domain.Supplier;
import com.techhouse.procurement.persistence.SupplierRepository;
import com.techhouse.procurement.web.dto.SupplierRequest;
import com.techhouse.procurement.web.dto.SupplierResponse;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final EntityManager em;

    @Transactional(readOnly = true)
    public List<SupplierResponse> list(UUID storeId, String q, Boolean active) {
        String term = q == null ? "" : q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return supplierRepository.search(storeId, term, active).stream().map(SupplierService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public SupplierResponse get(UUID storeId, UUID id) {
        return toResponse(find(storeId, id));
    }

    @Transactional
    public SupplierResponse create(UUID storeId, SupplierRequest request) {
        String name = request.name().trim();
        if (supplierRepository.existsByStoreIdAndNameIgnoreCase(storeId, name)) {
            throw nameExists(name);
        }
        Supplier supplier = new Supplier();
        supplier.setStoreId(storeId);
        apply(supplier, request, name);
        return toResponse(supplierRepository.saveAndFlush(supplier));
    }

    @Transactional
    public SupplierResponse update(UUID storeId, UUID id, SupplierRequest request) {
        Supplier supplier = find(storeId, id);
        if (request.version() == null) {
            throw BusinessException.onField(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "version", "version is required");
        }
        if (!request.version().equals(supplier.getVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                    "The record was modified by someone else");
        }
        String name = request.name().trim();
        if (supplierRepository.existsByStoreIdAndNameIgnoreCaseAndIdNot(storeId, name, id)) {
            throw nameExists(name);
        }
        apply(supplier, request, name);
        return toResponse(supplierRepository.saveAndFlush(supplier));
    }

    @Transactional
    public void delete(UUID storeId, UUID id) {
        Supplier supplier = find(storeId, id);
        Number invoices = (Number) em.createNativeQuery("select count(*) from purchase_invoices where supplier_id = :id")
                .setParameter("id", id).getSingleResult();
        if (invoices.longValue() > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "SUPPLIER_IN_USE",
                    "Supplier has purchase invoices; deactivate it instead");
        }
        supplierRepository.delete(supplier);
    }

    private static void apply(Supplier s, SupplierRequest r, String name) {
        s.setName(name);
        s.setContactInfo(blankToNull(r.contactInfo()));
        s.setNotes(blankToNull(r.notes()));
        if (r.active() != null) {
            s.setActive(r.active());
        }
    }

    private Supplier find(UUID storeId, UUID id) {
        return supplierRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new ResourceNotFoundException("SUPPLIER_NOT_FOUND", "Supplier", id));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static BusinessException nameExists(String name) {
        return BusinessException.onField(HttpStatus.CONFLICT, "SUPPLIER_NAME_EXISTS", "name",
                "Supplier name already exists: " + name);
    }

    static SupplierResponse toResponse(Supplier s) {
        return new SupplierResponse(s.getId(), s.getName(), s.getContactInfo(), s.getNotes(), s.isActive(),
                s.getVersion(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
