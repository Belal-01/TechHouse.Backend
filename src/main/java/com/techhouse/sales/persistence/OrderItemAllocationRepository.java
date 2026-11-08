package com.techhouse.sales.persistence;

import com.techhouse.sales.domain.OrderItemAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderItemAllocationRepository extends JpaRepository<OrderItemAllocation, UUID> {

    List<OrderItemAllocation> findByOrderItemIdIn(Collection<UUID> orderItemIds);
}
