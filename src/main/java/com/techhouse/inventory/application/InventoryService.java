package com.techhouse.inventory.application;

import com.techhouse.inventory.domain.InventoryLot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** FIFO stock movements. Runs inside the caller's transaction so stock and the order commit together. */
@Service
@RequiredArgsConstructor
public class InventoryService {

    /** Taken from one lot, at that lot's landed unit cost. */
    public record Allocation(UUID lotId, int quantity, BigDecimal unitCost) {}

    public static class InsufficientStockException extends RuntimeException {
        private final int available;

        public InsufficientStockException(int available) {
            super("Only " + available + " in stock");
            this.available = available;
        }

        public int available() {
            return available;
        }
    }

    private final EntityManager em;

    /**
     * Takes {@code quantity} from the oldest lots first. The lots are locked (SELECT ... FOR UPDATE), so two orders
     * at the same moment can never take the same units.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @SuppressWarnings("unchecked")
    public List<Allocation> allocate(UUID storeId, UUID productId, int quantity) {
        List<InventoryLot> lots = em.createNativeQuery("""
                        select * from inventory_lots
                        where store_id = :store and product_id = :product and qty_left > 0
                        order by received_at, id
                        for update""", InventoryLot.class)
                .setParameter("store", storeId)
                .setParameter("product", productId)
                .getResultList();
        int available = lots.stream().mapToInt(InventoryLot::getQtyLeft).sum();
        if (available < quantity) {
            throw new InsufficientStockException(available);
        }
        List<Allocation> taken = new ArrayList<>();
        int remaining = quantity;
        for (InventoryLot lot : lots) {
            if (remaining == 0) break;
            int take = Math.min(remaining, lot.getQtyLeft());
            lot.setQtyLeft(lot.getQtyLeft() - take);
            taken.add(new Allocation(lot.getId(), take, lot.getUnitCost()));
            remaining -= take;
        }
        return taken;
    }

    /** Puts units back into the lots they came from (returns and voided orders), at their original cost. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(List<Allocation> allocations) {
        for (Allocation a : allocations) {
            InventoryLot lot = em.find(InventoryLot.class, a.lotId(), LockModeType.PESSIMISTIC_WRITE);
            lot.setQtyLeft(lot.getQtyLeft() + a.quantity());
        }
    }
}
