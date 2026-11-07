package com.techhouse.procurement.persistence;

import com.techhouse.procurement.domain.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    @Query("""
            select s from Supplier s
            where s.storeId = :storeId
              and (:active is null or s.active = :active)
              and (:q = '' or lower(s.name) like lower(concat('%', :q, '%')) escape '\\')
            order by s.name asc
            """)
    List<Supplier> search(@Param("storeId") UUID storeId, @Param("q") String q, @Param("active") Boolean active);

    Optional<Supplier> findByIdAndStoreId(UUID id, UUID storeId);

    boolean existsByStoreIdAndNameIgnoreCase(UUID storeId, String name);

    boolean existsByStoreIdAndNameIgnoreCaseAndIdNot(UUID storeId, String name, UUID id);
}
