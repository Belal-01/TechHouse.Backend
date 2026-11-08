package com.techhouse.inventory.application;

import com.techhouse.inventory.web.dto.StockItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stock per product: in the warehouse (lots) and on the way to customers (pending orders), at landed cost. */
@Service
@RequiredArgsConstructor
public class StockService {

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<StockItem> list(UUID storeId) {
        record Transit(int qty, BigDecimal value) {}
        Map<UUID, Transit> transit = new HashMap<>();
        jdbc.query("""
                select oi.product_id, sum(a.quantity), sum(a.quantity * a.unit_cost)
                from order_item_allocations a
                join order_items oi on oi.id = a.order_item_id
                join orders o on o.id = oi.order_id
                where o.store_id = ? and o.status = 'PENDING'
                group by oi.product_id""",
                rs -> {
                    transit.put(rs.getObject(1, UUID.class), new Transit(rs.getInt(2), rs.getBigDecimal(3)));
                }, storeId);

        return jdbc.query("""
                select p.id, p.name, p.sku, p.selling_price, p.active,
                       coalesce(sum(l.qty_left), 0), coalesce(sum(l.qty_left * l.unit_cost), 0)
                from products p
                left join inventory_lots l on l.product_id = p.id and l.qty_left > 0
                where p.store_id = ? and p.deleted_at is null
                group by p.id
                order by p.name""",
                (rs, i) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    Transit t = transit.getOrDefault(id, new Transit(0, BigDecimal.ZERO));
                    return new StockItem(id, rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getBoolean(5),
                            rs.getInt(6), rs.getBigDecimal(7), t.qty(), t.value());
                }, storeId);
    }
}
