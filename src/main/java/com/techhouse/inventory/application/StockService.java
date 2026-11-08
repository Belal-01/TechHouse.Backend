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

    @org.springframework.beans.factory.annotation.Value("${app.dashboard.low-stock-threshold:3}")
    private int lowStockThreshold;

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
                       coalesce(sum(l.qty_left), 0), coalesce(sum(l.qty_left * l.unit_cost), 0), count(l.id) > 0
                from products p
                left join (select l.* from inventory_lots l
                           join invoice_items ii on ii.id = l.invoice_item_id
                           join purchase_invoices pi on pi.id = ii.invoice_id and pi.voided_at is null) l
                       on l.product_id = p.id
                where p.store_id = ? and p.deleted_at is null
                group by p.id
                order by p.name""",
                (rs, i) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    Transit t = transit.getOrDefault(id, new Transit(0, BigDecimal.ZERO));
                    boolean stocked = rs.getBoolean(8);
                    int available = rs.getInt(6);
                    return new StockItem(id, rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getBoolean(5),
                            available, rs.getBigDecimal(7), t.qty(), t.value(), stocked,
                            stocked && rs.getBoolean(5) && available < lowStockThreshold);
                }, storeId);
    }

    /** Totals, lots (FIFO order) and the last 20 orders that took this product. */
    @Transactional(readOnly = true)
    public com.techhouse.inventory.web.dto.ProductStock product(UUID storeId, UUID productId) {
        StockItem summary = list(storeId).stream().filter(s -> s.productId().equals(productId)).findFirst()
                .orElseThrow(() -> new com.techhouse.shared.exception.ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product", productId));
        var lots = jdbc.query("""
                select l.id, i.id, i.invoice_number, i.invoice_date, s.name, l.qty_in, l.qty_left, l.unit_cost, l.received_at
                from inventory_lots l
                join invoice_items ii on ii.id = l.invoice_item_id
                join purchase_invoices i on i.id = ii.invoice_id
                left join suppliers s on s.id = i.supplier_id
                where l.store_id = ? and l.product_id = ? and i.voided_at is null
                order by l.received_at, l.id""",
                (rs, i) -> new com.techhouse.inventory.web.dto.ProductStock.Lot(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, java.time.LocalDate.class), rs.getString(5),
                        rs.getInt(6), rs.getInt(7), rs.getBigDecimal(8), rs.getTimestamp(9).toInstant()), storeId, productId);
        var orders = jdbc.query("""
                select o.id, o.order_number, o.status, sum(oi.quantity),
                       (select sum(a.quantity * a.unit_cost) / nullif(sum(a.quantity), 0) from order_item_allocations a
                        join order_items x on x.id = a.order_item_id where x.order_id = o.id and x.product_id = ?),
                       o.ordered_at
                from orders o join order_items oi on oi.order_id = o.id
                where o.store_id = ? and oi.product_id = ?
                group by o.id, o.order_number, o.status, o.ordered_at
                order by o.ordered_at desc limit 20""",
                (rs, i) -> new com.techhouse.inventory.web.dto.ProductStock.Outgoing(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getInt(4), rs.getBigDecimal(5), rs.getTimestamp(6).toInstant()),
                productId, storeId, productId);
        return new com.techhouse.inventory.web.dto.ProductStock(summary, lowStockThreshold, lots, orders);
    }
}
