package com.techhouse.sales.persistence;

import com.techhouse.sales.domain.DeliveryCompany;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryCompanyRepository extends JpaRepository<DeliveryCompany, UUID> {

    List<DeliveryCompany> findByStoreIdOrderByNameAsc(UUID storeId);

    Optional<DeliveryCompany> findByIdAndStoreId(UUID id, UUID storeId);

    boolean existsByStoreIdAndNameIgnoreCase(UUID storeId, String name);

    boolean existsByStoreIdAndNameIgnoreCaseAndIdNot(UUID storeId, String name, UUID id);
}
