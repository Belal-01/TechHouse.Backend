package com.techhouse.procurement.application;

import com.techhouse.catalog.domain.Product;
import com.techhouse.catalog.persistence.ProductRepository;
import com.techhouse.finance.application.LedgerService;
import com.techhouse.inventory.domain.InventoryLot;
import com.techhouse.procurement.domain.InvoiceItem;
import com.techhouse.procurement.domain.PurchaseInvoice;
import com.techhouse.procurement.domain.Supplier;
import com.techhouse.procurement.persistence.InvoiceItemRepository;
import com.techhouse.procurement.persistence.PurchaseInvoiceFileRepository;
import com.techhouse.procurement.persistence.PurchaseInvoiceRepository;
import com.techhouse.procurement.persistence.SupplierRepository;
import com.techhouse.procurement.web.dto.InvoiceItemRequest;
import com.techhouse.procurement.web.dto.PurchaseInvoiceRequest;
import com.techhouse.procurement.web.dto.PurchaseInvoiceResponse;
import com.techhouse.procurement.web.dto.PurchaseInvoiceSummary;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Records what was bought, spreads shipping/customs over the items by value (landed cost),
 * opens one FIFO lot per line, and writes the payment to the cash ledger, all in one transaction.
 */
@Service
@RequiredArgsConstructor
public class PurchaseInvoiceService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String WALLET = "USD";

    private final PurchaseInvoiceRepository invoiceRepository;
    private final InvoiceItemRepository itemRepository;
    private final PurchaseInvoiceFileRepository fileRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final LedgerService ledger;
    private final EntityManager em;

    // ---- Queries ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<PurchaseInvoiceSummary> list(UUID storeId, UUID supplierId, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("invoiceDate"), Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        Page<PurchaseInvoice> result = invoiceRepository.search(storeId, supplierId, pageable);

        Set<UUID> ids = result.getContent().stream().map(PurchaseInvoice::getId).collect(Collectors.toSet());
        Map<UUID, List<InvoiceItem>> items = ids.isEmpty() ? Map.of()
                : itemRepository.findByInvoiceIdIn(ids).stream().collect(Collectors.groupingBy(InvoiceItem::getInvoiceId));
        Set<UUID> withFile = ids.isEmpty() ? Set.of() : Set.copyOf(fileRepository.findInvoiceIdsWithFile(ids));
        Map<UUID, Supplier> suppliers = suppliersFor(result.getContent());

        return PageResponse.from(result.map(inv -> {
            List<InvoiceItem> lines = items.getOrDefault(inv.getId(), List.of());
            return new PurchaseInvoiceSummary(inv.getId(), inv.getInvoiceNumber(), supplierRef(suppliers, inv),
                    inv.getInvoiceDate(), lines.size(), total(inv, lines), inv.getPaidFrom(),
                    withFile.contains(inv.getId()));
        }));
    }

    @Transactional(readOnly = true)
    public PurchaseInvoiceResponse get(UUID storeId, UUID id) {
        return toResponse(find(storeId, id));
    }

    // ---- Commands --------------------------------------------------------------------

    @Transactional
    public PurchaseInvoiceResponse create(UUID storeId, PurchaseInvoiceRequest request) {
        if (request.supplierId() != null) {
            Supplier supplier = supplierRepository.findByIdAndStoreId(request.supplierId(), storeId)
                    .orElseThrow(() -> BusinessException.onField(HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND",
                            "supplierId", "Supplier not found"));
            if (!supplier.isActive()) {
                throw BusinessException.onField(HttpStatus.CONFLICT, "SUPPLIER_INACTIVE", "supplierId",
                        "Supplier is inactive");
            }
        }
        Map<UUID, Product> products = loadProducts(storeId, request.items());

        BigDecimal shipping = money(request.shippingCostUsd());
        BigDecimal customs = money(request.customsCostUsd());

        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setStoreId(storeId);
        invoice.setSupplierId(request.supplierId());
        invoice.setInvoiceNumber(nextNumber());
        invoice.setInvoiceDate(request.invoiceDate());
        invoice.setShippingCostUsd(shipping);
        invoice.setCustomsCostUsd(customs);
        invoice.setPaidFrom(request.paidFrom());
        invoice.setNotes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim());
        em.persist(invoice);

        List<BigDecimal> extras = allocateExtra(request.items(), shipping.add(customs));
        Instant receivedAt = Instant.now();
        for (int i = 0; i < request.items().size(); i++) {
            InvoiceItemRequest line = request.items().get(i);
            BigDecimal extra = extras.get(i);
            BigDecimal price = line.unitPriceUsd().setScale(4, RoundingMode.HALF_EVEN);

            InvoiceItem item = new InvoiceItem();
            item.setInvoiceId(invoice.getId());
            item.setProductId(line.productId());
            item.setQuantity(line.quantity());
            item.setUnitPriceUsd(price);
            item.setExtraCostUsd(extra);
            item.setLandedUnitCost(price.add(extra.divide(BigDecimal.valueOf(line.quantity()), 6, RoundingMode.HALF_EVEN))
                    .setScale(6, RoundingMode.HALF_EVEN));
            em.persist(item);

            InventoryLot lot = new InventoryLot();
            lot.setStoreId(storeId);
            lot.setProductId(line.productId());
            lot.setInvoiceItemId(item.getId());
            lot.setQtyIn(line.quantity());
            lot.setQtyLeft(line.quantity());
            lot.setUnitCost(item.getLandedUnitCost());
            lot.setReceivedAt(receivedAt);
            em.persist(lot);
        }

        BigDecimal total = itemsTotal(request.items()).add(shipping).add(customs);
        if (Objects.equals(request.paidFrom(), "EXTERNAL_CAPITAL")) {
            // New money from the owner: in and straight out again, so cash is unchanged.
            ledger.record(storeId, WALLET, "CAPITAL_IN", total, "PURCHASE_INVOICE", invoice.getId(),
                    "Capital for " + invoice.getInvoiceNumber());
        }
        ledger.record(storeId, WALLET, "PURCHASE_PAYMENT", total.negate(), "PURCHASE_INVOICE", invoice.getId(),
                "Payment for " + invoice.getInvoiceNumber());

        em.flush();
        return toResponse(invoice);
    }

    // ---- Internals -------------------------------------------------------------------

    PurchaseInvoice find(UUID storeId, UUID id) {
        return invoiceRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new ResourceNotFoundException("INVOICE_NOT_FOUND", "Purchase invoice", id));
    }

    private Map<UUID, Product> loadProducts(UUID storeId, List<InvoiceItemRequest> items) {
        Set<UUID> ids = items.stream().map(InvoiceItemRequest::productId).collect(Collectors.toSet());
        Map<UUID, Product> found = productRepository.findAllById(ids).stream()
                .filter(p -> p.getStoreId().equals(storeId) && p.getDeletedAt() == null)
                .collect(Collectors.toMap(Product::getId, p -> p));
        for (int i = 0; i < items.size(); i++) {
            if (!found.containsKey(items.get(i).productId())) {
                throw BusinessException.onField(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                        "items[" + i + "].productId", "Product not found");
            }
        }
        return found;
    }

    /**
     * Each line's share of the extra costs is proportional to its value. Shares are rounded to 4 decimals and the
     * last line takes the rounding remainder, so the shares always add up to exactly the extra total.
     */
    static List<BigDecimal> allocateExtra(List<InvoiceItemRequest> items, BigDecimal extraTotal) {
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal itemsTotal = itemsTotal(items);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            BigDecimal share;
            if (extraTotal.signum() == 0) {
                share = BigDecimal.ZERO.setScale(4);
            } else if (i == items.size() - 1) {
                share = extraTotal.subtract(allocated);
            } else {
                share = lineValue(items.get(i)).multiply(extraTotal).divide(itemsTotal, 4, RoundingMode.HALF_EVEN);
            }
            allocated = allocated.add(share);
            shares.add(share);
        }
        return shares;
    }

    private static BigDecimal lineValue(InvoiceItemRequest line) {
        return line.unitPriceUsd().setScale(4, RoundingMode.HALF_EVEN).multiply(BigDecimal.valueOf(line.quantity()));
    }

    private static BigDecimal itemsTotal(List<InvoiceItemRequest> items) {
        return items.stream().map(PurchaseInvoiceService::lineValue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(4, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal total(PurchaseInvoice inv, List<InvoiceItem> lines) {
        return lines.stream().map(l -> l.getUnitPriceUsd().multiply(BigDecimal.valueOf(l.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(inv.getShippingCostUsd()).add(inv.getCustomsCostUsd());
    }

    private String nextNumber() {
        Number n = (Number) em.createNativeQuery("select nextval('purchase_invoice_seq')").getSingleResult();
        return "PI-%06d".formatted(n.longValue());
    }

    private Map<UUID, Supplier> suppliersFor(List<PurchaseInvoice> invoices) {
        Set<UUID> ids = invoices.stream().map(PurchaseInvoice::getSupplierId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return supplierRepository.findAllById(ids).stream().collect(Collectors.toMap(Supplier::getId, s -> s));
    }

    private static PurchaseInvoiceResponse.SupplierRef supplierRef(Map<UUID, Supplier> suppliers, PurchaseInvoice inv) {
        Supplier s = inv.getSupplierId() == null ? null : suppliers.get(inv.getSupplierId());
        return s == null ? null : new PurchaseInvoiceResponse.SupplierRef(s.getId(), s.getName());
    }

    private PurchaseInvoiceResponse toResponse(PurchaseInvoice inv) {
        List<InvoiceItem> lines = itemRepository.findByInvoiceIdIn(List.of(inv.getId()));
        Map<UUID, Product> products = new HashMap<>();
        productRepository.findAllById(lines.stream().map(InvoiceItem::getProductId).collect(Collectors.toSet()))
                .forEach(p -> products.put(p.getId(), p));
        List<PurchaseInvoiceResponse.Item> items = lines.stream().map(l -> {
            Product p = products.get(l.getProductId());
            return new PurchaseInvoiceResponse.Item(l.getId(),
                    new PurchaseInvoiceResponse.ProductRef(p.getId(), p.getName(), p.getSku()),
                    l.getQuantity(), l.getUnitPriceUsd(), l.getExtraCostUsd(), l.getLandedUnitCost(),
                    l.getUnitPriceUsd().multiply(BigDecimal.valueOf(l.getQuantity())));
        }).toList();
        BigDecimal itemsTotal = items.stream().map(PurchaseInvoiceResponse.Item::lineTotalUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PurchaseInvoiceResponse(inv.getId(), inv.getInvoiceNumber(),
                supplierRef(suppliersFor(List.of(inv)), inv), inv.getInvoiceDate(), inv.getShippingCostUsd(),
                inv.getCustomsCostUsd(), itemsTotal, total(inv, lines), inv.getPaidFrom(), inv.getNotes(),
                !fileRepository.findInvoiceIdsWithFile(List.of(inv.getId())).isEmpty(), items, inv.getVersion(),
                inv.getCreatedAt());
    }
}
