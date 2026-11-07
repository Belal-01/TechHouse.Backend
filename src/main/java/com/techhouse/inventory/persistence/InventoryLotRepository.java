package com.techhouse.inventory.persistence;

import com.techhouse.inventory.domain.InventoryLot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InventoryLotRepository extends JpaRepository<InventoryLot, UUID> {
}
