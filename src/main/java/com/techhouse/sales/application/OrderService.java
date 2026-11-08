package com.techhouse.sales.application;

import com.techhouse.catalog.domain.Product;
import com.techhouse.catalog.persistence.ProductRepository;
import com.techhouse.finance.application.CashAccountService;
import com.techhouse.finance.application.ExchangeRateService;
import com.techhouse.finance.domain.CashAccount;
import com.techhouse.finance.domain.ExpenseCategory;
import com.techhouse.finance.application.ExpenseService;
import com.techhouse.finance.application.LedgerService;
import com.techhouse.identity.config.CurrentUser;
import com.techhouse.inventory.application.InventoryService;
import com.techhouse.sales.domain.DeliveryCompany;
import com.techhouse.sales.domain.Order;
import com.techhouse.sales.domain.OrderItem;
import com.techhouse.sales.domain.OrderItemAllocation;
import com.techhouse.sales.domain.OrderStatus;
import com.techhouse.sales.domain.OrderStatusHistory;
import com.techhouse.sales.persistence.DeliveryCompanyRepository;
import com.techhouse.sales.persistence.OrderItemAllocationRepository;
import com.techhouse.sales.persistence.OrderItemRepository;
import com.techhouse.sales.persistence.OrderRepository;
import com.techhouse.sales.persistence.OrderStatusHistoryRepository;
import com.techhouse.sales.web.dto.OrderDetail;
import com.techhouse.sales.web.dto.OrderItemRequest;
import com.techhouse.sales.web.dto.OrderMoney;
import com.techhouse.sales.web.dto.OrderRequest;
import com.techhouse.sales.web.dto.OrderSummary;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import com.techhouse.shared.web.PageResponse;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Order lifecycle (spec §8). Entering an order takes the stock (FIFO), fixes the exchange rate and books the delivery
 * fee as an expense. Then exactly one final step: delivered (money in), returned (stock back) or voided (all undone).
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String REF = "ORDER";

    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderItemAllocationRepository allocations;
    private final OrderStatusHistoryRepository history;
    private final DeliveryCompanyRepository companies;
    private final ProductRepository products;
    private final InventoryService inventory;
    private final ExpenseService expenses;
    private final LedgerService ledger;
    private final ExchangeRateService rates;
    private final CashAccountService accounts;
    private final EntityManager em;

    // ---- Queries ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> list(UUID storeId, OrderStatus status, String q, int page, int size) {
        String term = q == null ? "" : q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("orderedAt"), Sort.Order.desc("id")));
        Page<Order> result = orders.search(storeId, status, term, pageable);

        Set<UUID> ids = result.getContent().stream().map(Order::getId).collect(Collectors.toSet());
        Lines lines = lines(ids);
        Map<UUID, DeliveryCompany> companyById = companiesFor(result.getContent());
        return PageResponse.from(result.map(o -> new OrderSummary(o.getId(), o.getOrderNumber(), o.getStatus(),
                o.getCustomerName(), o.getCustomerPhone(), o.getCurrency(), o.customerTotal(),
                lines.itemsOf(o.getId()).size(), ref(companyById.get(o.getDeliveryCompanyId())),
                money(o, lines.costOf(o.getId())), o.getOrderedAt())));
    }

    @Transactional(readOnly = true)
    public OrderDetail get(UUID storeId, UUID id) {
        return toDetail(orders.findByIdAndStoreId(id, storeId).orElseThrow(() -> notFound(id)));
    }

    // ---- Commands --------------------------------------------------------------------

    @Transactional
    public OrderDetail create(UUID storeId, OrderRequest request) {
        BigDecimal feeCharged = amount(request.deliveryFeeCharged());
        BigDecimal deliveryCost = amount(request.deliveryCost());
        String costCurrency = deliveryCost.signum() > 0
                ? Objects.requireNonNullElse(request.deliveryCostCurrency(), "SYP") : null;

        DeliveryCompany company = null;
        if (request.deliveryCompanyId() != null) {
            company = companies.findByIdAndStoreId(request.deliveryCompanyId(), storeId)
                    .orElseThrow(() -> BusinessException.onField(HttpStatus.NOT_FOUND, "DELIVERY_COMPANY_NOT_FOUND",
                            "deliveryCompanyId", "Delivery company not found"));
            if (!company.isActive()) {
                throw BusinessException.onField(HttpStatus.CONFLICT, "DELIVERY_COMPANY_INACTIVE", "deliveryCompanyId",
                        "Delivery company is inactive");
            }
        }

        // The sell rate is fixed now; it is required as soon as anything is in SYP.
        boolean needsRate = "SYP".equals(request.currency()) || "SYP".equals(costCurrency);
        ExchangeRateService.SaleRate rate = rates.saleRate(storeId).orElse(null);
        if (needsRate && rate == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "EXCHANGE_RATE_REQUIRED",
                    "No exchange rate yet; enter today's rate first");
        }

        Map<UUID, Product> productById = loadProducts(storeId, request.items());
        CashAccount deliveryAccount = deliveryCost.signum() > 0
                ? accounts.resolve(storeId, request.deliveryAccountId(), "deliveryAccountId") : null;

        Order order = new Order();
        order.setStoreId(storeId);
        order.setOrderNumber(nextNumber());
        order.setCurrency(request.currency());
        order.setExchangeRateId(rate == null ? null : rate.id());
        order.setExchangeRateSnapshot(rate == null ? null : rate.sellRate());
        order.setItemsTotal(request.items().stream()
                .map(l -> l.unitPrice().multiply(BigDecimal.valueOf(l.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(4, RoundingMode.HALF_UP));
        order.setDeliveryFeeCharged(feeCharged);
        order.setDeliveryCost(deliveryCost);
        order.setDeliveryCostCurrency(costCurrency);
        order.setDeliveryCompanyId(company == null ? null : company.getId());
        order.setCustomerName(request.customerName().trim());
        order.setCustomerPhone(blankToNull(request.customerPhone()));
        order.setCustomerAddress(blankToNull(request.customerAddress()));
        order.setNotes(blankToNull(request.notes()));
        order.setOrderedAt(Instant.now());
        em.persist(order);

        for (int i = 0; i < request.items().size(); i++) {
            OrderItemRequest line = request.items().get(i);
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(line.productId());
            item.setQuantity(line.quantity());
            item.setUnitPrice(line.unitPrice().setScale(4, RoundingMode.HALF_UP));
            em.persist(item);
            List<InventoryService.Allocation> taken;
            try {
                taken = inventory.allocate(storeId, line.productId(), line.quantity());
            } catch (InventoryService.InsufficientStockException e) {
                throw BusinessException.onField(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "items[" + i + "].quantity",
                        "Only " + e.available() + " of " + productById.get(line.productId()).getName() + " in stock");
            }
            for (InventoryService.Allocation a : taken) {
                OrderItemAllocation allocation = new OrderItemAllocation();
                allocation.setOrderItemId(item.getId());
                allocation.setLotId(a.lotId());
                allocation.setQuantity(a.quantity());
                allocation.setUnitCost(a.unitCost());
                em.persist(allocation);
            }
        }

        if (deliveryCost.signum() > 0) {
            expenses.record(new ExpenseService.Draft(storeId, order.getId(),
                    expenses.systemCategory(storeId, ExpenseCategory.DELIVERY),
                    "توصيل الطلب " + order.getOrderNumber() + (company == null ? "" : " (" + company.getName() + ")"),
                    deliveryCost, costCurrency, deliveryAccount.getId(), rate == null ? null : rate.id(),
                    rate == null ? null : rate.sellRate(), today(storeId)));
        }
        log(order, null, OrderStatus.PENDING, null);
        em.flush();
        return toDetail(order);
    }

    /** The customer received it: the full amount goes into the wallet of the order's currency. */
    @Transactional
    public OrderDetail deliver(UUID storeId, UUID id, UUID accountId, String note) {
        Order order = lockPending(storeId, id);
        CashAccount account = accounts.resolve(storeId, accountId, "accountId");
        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(Instant.now());
        order.setPaymentAccountId(account.getId());
        ledger.record(LedgerService.Entry.of(storeId, account.getId(), order.getCurrency(), "SALE_PAYMENT", order.customerTotal())
                .rate(order.getExchangeRateId(), order.getExchangeRateSnapshot()).ref(REF, order.getId())
                .note("تحصيل الطلب " + order.getOrderNumber()));
        log(order, OrderStatus.PENDING, OrderStatus.DELIVERED, note);
        em.flush();
        return toDetail(order);
    }

    /** Not received: the units go back to their lots; the delivery fee stays an expense (the cost of the return). */
    @Transactional
    public OrderDetail markReturned(UUID storeId, UUID id, String note) {
        Order order = lockPending(storeId, id);
        inventory.restore(allocationsOf(order.getId()));
        order.setStatus(OrderStatus.RETURNED);
        order.setReturnType("UNDELIVERED");
        order.setReturnedAt(Instant.now());
        log(order, OrderStatus.PENDING, OrderStatus.RETURNED, note);
        em.flush();
        return toDetail(order);
    }

    /** Entered by mistake: stock back, delivery expense voided and its money returned to the wallet. */
    @Transactional
    public OrderDetail voidOrder(UUID storeId, UUID id, String note) {
        Order order = lockPending(storeId, id);
        inventory.restore(allocationsOf(order.getId()));
        expenses.voidForOrder(storeId, order.getId(), "إلغاء الطلب " + order.getOrderNumber());
        order.setStatus(OrderStatus.VOIDED);
        order.setVoidedAt(Instant.now());
        log(order, OrderStatus.PENDING, OrderStatus.VOIDED, note);
        em.flush();
        return toDetail(order);
    }

    // ---- Internals -------------------------------------------------------------------

    private Order lockPending(UUID storeId, UUID id) {
        Order order = orders.lockByIdAndStoreId(id, storeId).orElseThrow(() -> notFound(id));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_NOT_PENDING",
                    "Order is already " + order.getStatus());
        }
        return order;
    }

    private Map<UUID, Product> loadProducts(UUID storeId, List<OrderItemRequest> lines) {
        Set<UUID> ids = lines.stream().map(OrderItemRequest::productId).collect(Collectors.toSet());
        Map<UUID, Product> found = products.findAllById(ids).stream()
                .filter(p -> p.getStoreId().equals(storeId) && p.getDeletedAt() == null)
                .collect(Collectors.toMap(Product::getId, p -> p));
        for (int i = 0; i < lines.size(); i++) {
            Product p = found.get(lines.get(i).productId());
            if (p == null) {
                throw BusinessException.onField(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "items[" + i + "].productId",
                        "Product not found");
            }
            if (!p.isActive()) {
                throw BusinessException.onField(HttpStatus.CONFLICT, "PRODUCT_INACTIVE", "items[" + i + "].productId",
                        "Product is not for sale: " + p.getName());
            }
        }
        return found;
    }

    private List<InventoryService.Allocation> allocationsOf(UUID orderId) {
        List<UUID> itemIds = items.findByOrderIdIn(List.of(orderId)).stream().map(OrderItem::getId).toList();
        return allocations.findByOrderItemIdIn(itemIds).stream()
                .map(a -> new InventoryService.Allocation(a.getLotId(), a.getQuantity(), a.getUnitCost()))
                .toList();
    }

    private void log(Order order, OrderStatus from, OrderStatus to, String note) {
        OrderStatusHistory h = new OrderStatusHistory();
        h.setOrderId(order.getId());
        h.setFromStatus(from == null ? null : from.name());
        h.setToStatus(to.name());
        h.setChangedBy(CurrentUser.idOrNull());
        h.setNote(blankToNull(note));
        em.persist(h);
    }

    /** Items, their FIFO cost and line costs for a set of orders, loaded in two queries. */
    private record Lines(Map<UUID, List<OrderItem>> byOrder, Map<UUID, BigDecimal> costByItem) {
        List<OrderItem> itemsOf(UUID orderId) {
            return byOrder.getOrDefault(orderId, List.of());
        }

        BigDecimal costOf(UUID orderId) {
            return itemsOf(orderId).stream().map(i -> costByItem.getOrDefault(i.getId(), BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private Lines lines(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) return new Lines(Map.of(), Map.of());
        List<OrderItem> all = items.findByOrderIdIn(orderIds);
        Map<UUID, BigDecimal> cost = all.isEmpty() ? Map.of()
                : allocations.findByOrderItemIdIn(all.stream().map(OrderItem::getId).toList()).stream()
                .collect(Collectors.groupingBy(OrderItemAllocation::getOrderItemId, Collectors.reducing(BigDecimal.ZERO,
                        a -> a.getUnitCost().multiply(BigDecimal.valueOf(a.getQuantity())), BigDecimal::add)));
        return new Lines(all.stream().collect(Collectors.groupingBy(OrderItem::getOrderId)), cost);
    }

    /** Spec §4: revenue = (items + delivery charged) at the fixed rate; profit = revenue − FIFO cost − delivery cost. */
    static OrderMoney money(Order o, BigDecimal cogs) {
        BigDecimal rate = o.getExchangeRateSnapshot();
        BigDecimal revenue = ExpenseService.toUsd(o.customerTotal(), o.getCurrency(), rate);
        BigDecimal delivery = o.getDeliveryCost().signum() == 0 || o.getStatus() == OrderStatus.VOIDED
                ? BigDecimal.ZERO.setScale(4)
                : ExpenseService.toUsd(o.getDeliveryCost(), o.getDeliveryCostCurrency(), rate);
        BigDecimal cost = cogs.setScale(4, RoundingMode.HALF_UP);
        BigDecimal profit = switch (o.getStatus()) {
            case PENDING, DELIVERED -> revenue.subtract(cost).subtract(delivery);
            case RETURNED -> delivery.negate();
            case VOIDED -> BigDecimal.ZERO.setScale(4);
        };
        return new OrderMoney(revenue, cost, delivery, profit);
    }

    private OrderDetail toDetail(Order o) {
        Lines lines = lines(List.of(o.getId()));
        List<OrderItem> orderItems = lines.itemsOf(o.getId());
        Map<UUID, Product> productById = products.findAllById(
                orderItems.stream().map(OrderItem::getProductId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        List<OrderDetail.Item> detailItems = orderItems.stream().map(i -> {
            Product p = productById.get(i.getProductId());
            return new OrderDetail.Item(i.getId(), new OrderDetail.ProductRef(p.getId(), p.getName(), p.getSku()),
                    i.getQuantity(), i.getUnitPrice(), i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())),
                    lines.costByItem().getOrDefault(i.getId(), BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP));
        }).toList();
        List<OrderDetail.History> events = history.findByOrderIdOrderByCreatedAtAsc(o.getId()).stream()
                .map(h -> new OrderDetail.History(h.getFromStatus() == null ? null : OrderStatus.valueOf(h.getFromStatus()),
                        OrderStatus.valueOf(h.getToStatus()), h.getNote(), h.getCreatedAt()))
                .toList();
        DeliveryCompany company = o.getDeliveryCompanyId() == null ? null
                : companies.findById(o.getDeliveryCompanyId()).orElse(null);
        return new OrderDetail(o.getId(), o.getOrderNumber(), o.getStatus(),
                new OrderDetail.Customer(o.getCustomerName(), o.getCustomerPhone(), o.getCustomerAddress()),
                o.getCurrency(), o.getExchangeRateSnapshot(), detailItems, o.getItemsTotal(), o.getDeliveryFeeCharged(),
                o.customerTotal(), o.getDeliveryCost(), o.getDeliveryCostCurrency(), ref(company),
                accountRef(o.getPaymentAccountId()), money(o, lines.costOf(o.getId())), o.getNotes(), o.getOrderedAt(), o.getDeliveredAt(),
                o.getReturnedAt(), o.getVoidedAt(), events, o.getVersion());
    }

    private OrderDetail.Ref accountRef(UUID accountId) {
        if (accountId == null) return null;
        String name = (String) em.createNativeQuery("select name from cash_accounts where id = :id")
                .setParameter("id", accountId).getSingleResult();
        return new OrderDetail.Ref(accountId, name);
    }

    private Map<UUID, DeliveryCompany> companiesFor(List<Order> list) {
        Set<UUID> ids = list.stream().map(Order::getDeliveryCompanyId).filter(Objects::nonNull).collect(Collectors.toSet());
        return companies.findAllById(ids).stream().collect(Collectors.toMap(DeliveryCompany::getId, c -> c));
    }

    private static OrderDetail.Ref ref(DeliveryCompany c) {
        return c == null ? null : new OrderDetail.Ref(c.getId(), c.getName());
    }

    private String nextNumber() {
        Number n = (Number) em.createNativeQuery("select nextval('order_number_seq')").getSingleResult();
        return "SO-%06d".formatted(n.longValue());
    }

    private LocalDate today(UUID storeId) {
        String zone = (String) em.createNativeQuery("select timezone from stores where id = :id")
                .setParameter("id", storeId).getSingleResult();
        return LocalDate.now(ZoneId.of(zone));
    }

    private static BigDecimal amount(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(4, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException("ORDER_NOT_FOUND", "Order", id);
    }
}
