package com.techhouse.sales.persistence;

import com.techhouse.sales.domain.Order;
import com.techhouse.sales.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByIdAndStoreId(UUID id, UUID storeId);

    /** For status changes: locks the row so two clicks can't both deliver (or return) the same order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.storeId = :storeId")
    Optional<Order> lockByIdAndStoreId(@Param("id") UUID id, @Param("storeId") UUID storeId);

    /** {@code q} is already lower-cased and LIKE-escaped. */
    @Query("""
            select o from Order o
            where o.storeId = :storeId
              and (:status is null or o.status = :status)
              and (:q = '' or lower(o.orderNumber) like concat('%', :q, '%') escape '\\'
                   or lower(coalesce(o.customerName, '')) like concat('%', :q, '%') escape '\\'
                   or coalesce(o.customerPhone, '') like concat('%', :q, '%') escape '\\')
            """)
    Page<Order> search(@Param("storeId") UUID storeId, @Param("status") OrderStatus status, @Param("q") String q,
                       Pageable pageable);

    boolean existsByDeliveryCompanyId(UUID deliveryCompanyId);
}
