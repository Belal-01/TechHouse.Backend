package com.techhouse.procurement.application;

import com.techhouse.catalog.domain.Product;
import com.techhouse.catalog.persistence.ProductRepository;
import com.techhouse.finance.application.CashAccountService;
import com.techhouse.finance.application.ExchangeRateService;
import com.techhouse.finance.application.ExpenseService;
import com.techhouse.finance.application.LedgerService;
import com.techhouse.finance.application.PartnerService;
import com.techhouse.finance.domain.CashAccount;
import com.techhouse.inventory.domain.InventoryLot;
import com.techhouse.procurement.domain.InvoiceCost;
import com.techhouse.procurement.domain.InvoiceItem;
import com.techhouse.procurement.domain.InvoicePayment;
import com.techhouse.procurement.domain.PurchaseInvoice;
import com.techhouse.procurement.domain.Supplier;
import com.techhouse.procurement.persistence.InvoiceCostRepository;
import com.techhouse.procurement.persistence.InvoiceItemRepository;
import com.techhouse.procurement.persistence.InvoicePaymentRepository;
import com.techhouse.procurement.persistence.PurchaseInvoiceFileRepository;
import com.techhouse.procurement.persistence.PurchaseInvoiceRepository;
import com.techhouse.procurement.persistence.SupplierRepository;
import com.techhouse.procurement.web.dto.AssignFundingRequest;
import com.techhouse.procurement.web.dto.InvoiceCostRequest;
import com.techhouse.procurement.web.dto.InvoiceItemRequest;
import com.techhouse.procurement.web.dto.InvoicePaymentRequest;
import com.techhouse.procurement.web.dto.PurchaseInvoiceRequest;
import com.techhouse.procurement.web.dto.PurchaseInvoiceResponse;
import com.techhouse.procurement.web.dto.PurchaseInvoiceSummary;
import com.techhouse.procurement.web.dto.ReceiveInvoiceRequest;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import com.techhouse.shared.web.PageResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Purchase invoices in two stages. On the way (IN_TRANSIT): the goods are bought and paid, costs can be added, but
 * nothing is sellable. Received (RECEIVED): every cost is spread over the lines by value (landed cost) and one FIFO
 * lot per line opens. A cost added after that is spread over the units still in stock.
 * <p>
 * Every part of the invoice records who paid it: a store box (a cash movement out of it), or an owner's or
 * investor's own money (no cash movement: that money never went through a box).
 */
@Service
@RequiredArgsConstructor
public class PurchaseInvoiceService {

    public static final String IN_TRANSIT = "IN_TRANSIT";
    public static final String RECEIVED = "RECEIVED";

    private static final int MAX_PAGE_SIZE = 100;
    /** How far the payments may be from the total they pay (rounding when splitting); the last part absorbs it. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final Map<String, String> COST_LABELS = Map.of(
            "CHINA_SHIPPING", "شحن داخل الصين", "SEA_FREIGHT", "شحن بحري", "CUSTOMS", "جمارك", "OTHER", "تكلفة");

    private final PurchaseInvoiceRepository invoiceRepository;
    private final InvoiceItemRepository itemRepository;
    private final InvoiceCostRepository costRepository;
    private final InvoicePaymentRepository paymentRepository;
    private final PurchaseInvoiceFileRepository fileRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final LedgerService ledger;
    private final CashAccountService accounts;
    private final PartnerService partners;
    private final ExpenseService expenses;
    private final EntityManager em;

    // ---- Queries ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<PurchaseInvoiceSummary> list(UUID storeId, UUID supplierId, String status, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("invoiceDate"), Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        String statusFilter = status == null || status.isBlank() ? null : status;
        Page<PurchaseInvoice> result = invoiceRepository.search(storeId, supplierId, statusFilter, pageable);

        Set<UUID> ids = result.getContent().stream().map(PurchaseInvoice::getId).collect(Collectors.toSet());
        Map<UUID, List<InvoiceItem>> items = ids.isEmpty() ? Map.of()
                : itemRepository.findByInvoiceIdIn(ids).stream().collect(Collectors.groupingBy(InvoiceItem::getInvoiceId));
        Map<UUID, BigDecimal> costs = new HashMap<>();
        Set<UUID> unassigned = new HashSet<>();
        if (!ids.isEmpty()) {
            costRepository.findByInvoiceIdInOrderByCreatedAtAsc(ids)
                    .forEach(c -> costs.merge(c.getInvoiceId(), c.getAmountUsd(), BigDecimal::add));
            paymentRepository.findByInvoiceIdInOrderByCreatedAtAsc(ids).stream()
                    .filter(p -> InvoicePayment.EXTERNAL.equals(p.getSource()))
                    .forEach(p -> unassigned.add(p.getInvoiceId()));
        }
        Set<UUID> withFile = ids.isEmpty() ? Set.of() : Set.copyOf(fileRepository.findInvoiceIdsWithFile(ids));
        Map<UUID, Supplier> suppliers = suppliersFor(result.getContent());

        return PageResponse.from(result.map(inv -> {
            List<InvoiceItem> lines = items.getOrDefault(inv.getId(), List.of());
            BigDecimal total = goodsTotal(lines).add(costs.getOrDefault(inv.getId(), BigDecimal.ZERO));
            return new PurchaseInvoiceSummary(inv.getId(), inv.getInvoiceNumber(), supplierRef(suppliers, inv),
                    inv.getInvoiceDate(), inv.getStatus(), lines.size(), total, withFile.contains(inv.getId()),
                    inv.getVoidedAt() != null, inv.getVoidedAt() == null && unassigned.contains(inv.getId()));
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
        loadProducts(storeId, request.items());

        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setStoreId(storeId);
        invoice.setSupplierId(request.supplierId());
        invoice.setInvoiceNumber(nextNumber());
        invoice.setInvoiceDate(request.invoiceDate());
        invoice.setStatus(IN_TRANSIT);
        invoice.setNotes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim());
        em.persist(invoice);

        List<InvoiceItem> items = new ArrayList<>();
        for (int i = 0; i < request.items().size(); i++) {
            InvoiceItemRequest line = request.items().get(i);
            BigDecimal price = line.unitPriceUsd().setScale(4, RoundingMode.HALF_EVEN);
            InvoiceItem item = new InvoiceItem();
            item.setInvoiceId(invoice.getId());
            item.setProductId(line.productId());
            item.setQuantity(line.quantity());
            item.setPosition(i);
            item.setUnitPriceUsd(price);
            item.setExtraCostUsd(BigDecimal.ZERO.setScale(4));
            item.setLandedUnitCost(price.setScale(6, RoundingMode.HALF_EVEN));
            em.persist(item);
            items.add(item);
        }

        payGoods(storeId, invoice, goodsTotal(items), request.payments());
        List<InvoiceCostRequest> costs = request.costs() == null ? List.of() : request.costs();
        for (int i = 0; i < costs.size(); i++) {
            addCostLine(storeId, invoice, costs.get(i), false, "costs[" + i + "]");
        }
        reprice(items, costTotal(invoice.getId()));
        if (RECEIVED.equals(request.status())) openLots(invoice, items);

        em.flush();
        return toResponse(invoice);
    }

    /** The goods arrived: record the arrival costs, fix each unit's cost and put the units in stock. */
    @Transactional
    public PurchaseInvoiceResponse receive(UUID storeId, UUID id, ReceiveInvoiceRequest request) {
        PurchaseInvoice invoice = lock(storeId, id);
        requireNotVoided(invoice);
        if (RECEIVED.equals(invoice.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVOICE_ALREADY_RECEIVED", "The goods already arrived");
        }
        List<InvoiceCostRequest> costs = request == null || request.costs() == null ? List.of() : request.costs();
        for (int i = 0; i < costs.size(); i++) {
            addCostLine(storeId, invoice, costs.get(i), false, "costs[" + i + "]");
        }
        List<InvoiceItem> items = itemRepository.findByInvoiceIdOrderByPositionAscIdAsc(id);
        reprice(items, costTotal(id));
        openLots(invoice, items);
        em.flush();
        return toResponse(invoice);
    }

    /**
     * A cost that came up later. On the way, it is simply part of the costs. After arrival, it is spread over the units
     * still in stock: the ones already sold or taken by an order keep the cost they went out at.
     */
    @Transactional
    public PurchaseInvoiceResponse addCost(UUID storeId, UUID id, InvoiceCostRequest request) {
        PurchaseInvoice invoice = lock(storeId, id);
        requireNotVoided(invoice);
        if (IN_TRANSIT.equals(invoice.getStatus())) {
            addCostLine(storeId, invoice, request, false, "");
            reprice(itemRepository.findByInvoiceIdOrderByPositionAscIdAsc(id), costTotal(id));
        } else {
            List<Object[]> lots = lockLots(id);
            if (lots.stream().noneMatch(l -> ((Number) l[1]).intValue() > 0)) {
                throw new BusinessException(HttpStatus.CONFLICT, "INVOICE_FULLY_SOLD",
                        "Nothing of this invoice is left in stock; record the cost as an expense");
            }
            InvoiceCost cost = addCostLine(storeId, invoice, request, true, "");
            spreadOverStock(id, lots, cost.getAmountUsd());
        }
        em.flush();
        return toResponse(invoice);
    }

    /** Removes a cost entered by mistake, while the goods are still on the way; a box payment for it is refunded. */
    @Transactional
    public PurchaseInvoiceResponse removeCost(UUID storeId, UUID id, UUID costId) {
        PurchaseInvoice invoice = lock(storeId, id);
        requireNotVoided(invoice);
        InvoiceCost cost = em.find(InvoiceCost.class, costId);
        if (cost == null || !cost.getInvoiceId().equals(id)) {
            throw new ResourceNotFoundException("INVOICE_COST_NOT_FOUND", "Invoice cost", costId);
        }
        if (!IN_TRANSIT.equals(invoice.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVOICE_COST_LOCKED",
                    "Costs can't be removed after the goods arrived");
        }
        List<InvoicePayment> payments = em.createQuery("select p from InvoicePayment p where p.costId = :cost", InvoicePayment.class)
                .setParameter("cost", costId).getResultList();
        String reason = "حذف " + COST_LABELS.get(cost.getKind()) + " من الفاتورة " + invoice.getInvoiceNumber();
        for (InvoicePayment p : payments) {
            reverseLedger(storeId, id, p.getId(), reason);
            em.remove(p);
        }
        em.remove(cost);
        em.flush();
        reprice(itemRepository.findByInvoiceIdOrderByPositionAscIdAsc(id), costTotal(id));
        em.flush();
        return toResponse(invoice);
    }

    /**
     * Says whose money an "outside money" payment was (invoices entered before payment sources existed): replaces it
     * with one payment per person. No cash moves: the money never went through a box.
     */
    @Transactional
    public PurchaseInvoiceResponse assignFunding(UUID storeId, UUID id, UUID paymentId, AssignFundingRequest request) {
        PurchaseInvoice invoice = lock(storeId, id);
        requireNotVoided(invoice);
        InvoicePayment payment = em.find(InvoicePayment.class, paymentId);
        if (payment == null || !payment.getInvoiceId().equals(id)) {
            throw new ResourceNotFoundException("INVOICE_PAYMENT_NOT_FOUND", "Invoice payment", paymentId);
        }
        if (!InvoicePayment.EXTERNAL.equals(payment.getSource())) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_ALREADY_ASSIGNED", "This payment already has a payer");
        }
        List<AssignFundingRequest.Part> parts = request.parts();
        BigDecimal sum = parts.stream().map(p -> scale(p.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.subtract(payment.getAmount()).abs().compareTo(TOLERANCE) > 0) {
            throw BusinessException.onField(HttpStatus.BAD_REQUEST, "FUNDING_TOTAL_MISMATCH", "parts",
                    "The parts must add up to " + payment.getAmount().stripTrailingZeros().toPlainString());
        }
        BigDecimal amountLeft = payment.getAmount();
        BigDecimal usdLeft = payment.getAmountUsd();
        for (int i = 0; i < parts.size(); i++) {
            AssignFundingRequest.Part part = parts.get(i);
            partners.requirePayer(storeId, part.partnerId(), part.source(), "parts[" + i + "].partnerId");
            boolean last = i == parts.size() - 1;
            BigDecimal amount = last ? amountLeft : scale(part.amount());
            BigDecimal usd = last ? usdLeft
                    : amount.multiply(payment.getAmountUsd()).divide(payment.getAmount(), 4, RoundingMode.HALF_EVEN);
            if (amount.signum() <= 0 || usd.signum() <= 0) {
                throw BusinessException.onField(HttpStatus.BAD_REQUEST, "FUNDING_TOTAL_MISMATCH", "parts", "Each part must be above zero");
            }
            amountLeft = amountLeft.subtract(amount);
            usdLeft = usdLeft.subtract(usd);
            InvoicePayment p = new InvoicePayment();
            p.setInvoiceId(id);
            p.setCostId(payment.getCostId());
            p.setSource(part.source());
            p.setPartnerId(part.partnerId());
            p.setAmount(amount);
            p.setCurrency(payment.getCurrency());
            p.setAmountUsd(usd);
            em.persist(p);
        }
        em.remove(payment);
        em.flush();
        return toResponse(invoice);
    }

    /**
     * Undo an invoice entered by mistake. Only while none of its units were sold or taken by an order: the stock
     * leaves, and every box payment it made is reversed with an opposite entry in the same box.
     */
    @Transactional
    public PurchaseInvoiceResponse voidInvoice(UUID storeId, UUID id, String note) {
        PurchaseInvoice invoice = lock(storeId, id);
        if (invoice.getVoidedAt() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVOICE_ALREADY_VOIDED", "The invoice is already voided");
        }
        lockLots(id);
        int used = unitsUsed(id);
        if (used > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVOICE_STOCK_USED",
                    used + " units of this invoice were already sold or are on the way");
        }
        em.createNativeQuery("""
                update inventory_lots set qty_left = 0
                where invoice_item_id in (select id from invoice_items where invoice_id = :invoice)""")
                .setParameter("invoice", id).executeUpdate();
        reverseLedger(storeId, id, null, "إلغاء الفاتورة " + invoice.getInvoiceNumber());
        invoice.setVoidedAt(Instant.now());
        invoice.setVoidNote(note == null || note.isBlank() ? null : note.trim());
        em.flush();
        return toResponse(invoice);
    }

    // ---- Costs and payments ----------------------------------------------------------

    private InvoiceCost addCostLine(UUID storeId, PurchaseInvoice invoice, InvoiceCostRequest r, boolean afterReceipt,
                                    String field) {
        String prefix = field.isEmpty() ? "" : field + ".";
        BigDecimal amount = scale(r.amount());
        ExchangeRateService.SaleRate rate = "SYP".equals(r.currency()) ? expenses.rateFor(invoice.getStoreId(), "SYP") : null;
        BigDecimal usd = ExpenseService.toUsd(amount, r.currency(), rate == null ? null : rate.sellRate());
        if (usd.signum() <= 0) {
            throw BusinessException.onField(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", prefix + "amount", "Amount is too small");
        }
        InvoiceCost cost = new InvoiceCost();
        cost.setInvoiceId(invoice.getId());
        cost.setKind(r.kind());
        cost.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        cost.setAmount(amount);
        cost.setCurrency(r.currency());
        cost.setExchangeRateId(rate == null ? null : rate.id());
        cost.setExchangeRateSnapshot(rate == null ? null : rate.sellRate());
        cost.setAmountUsd(usd);
        cost.setAfterReceipt(afterReceipt);
        em.persist(cost);

        String label = COST_LABELS.get(r.kind()) + " للفاتورة " + invoice.getInvoiceNumber();
        pay(storeId, invoice, cost.getId(), r.source(), r.accountId(), r.partnerId(), amount, r.currency(), usd, rate,
                prefix, label);
        return cost;
    }

    /** The goods payments must add up to the goods total; the last one absorbs a cent of rounding. */
    private void payGoods(UUID storeId, PurchaseInvoice invoice, BigDecimal total, List<InvoicePaymentRequest> payments) {
        BigDecimal sum = payments.stream().map(p -> scale(p.amountUsd())).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.subtract(total).abs().compareTo(TOLERANCE) > 0) {
            throw BusinessException.onField(HttpStatus.BAD_REQUEST, "PAYMENTS_TOTAL_MISMATCH", "payments",
                    "The payments must add up to the goods total " + total.stripTrailingZeros().toPlainString());
        }
        BigDecimal left = total;
        for (int i = 0; i < payments.size(); i++) {
            InvoicePaymentRequest p = payments.get(i);
            BigDecimal amount = i == payments.size() - 1 ? left : scale(p.amountUsd());
            if (amount.signum() <= 0) {
                throw BusinessException.onField(HttpStatus.BAD_REQUEST, "PAYMENTS_TOTAL_MISMATCH", "payments",
                        "Each payment must be above zero");
            }
            left = left.subtract(amount);
            pay(storeId, invoice, null, p.source(), p.accountId(), p.partnerId(), amount, "USD", amount, null,
                    "payments[" + i + "].", "بضاعة الفاتورة " + invoice.getInvoiceNumber());
        }
    }

    private void pay(UUID storeId, PurchaseInvoice invoice, UUID costId, String source, UUID accountId, UUID partnerId,
                     BigDecimal amount, String currency, BigDecimal usd, ExchangeRateService.SaleRate rate, String prefix,
                     String label) {
        InvoicePayment p = new InvoicePayment();
        p.setInvoiceId(invoice.getId());
        p.setCostId(costId);
        p.setSource(source);
        p.setAmount(amount);
        p.setCurrency(currency);
        p.setAmountUsd(usd);
        CashAccount account = null;
        if (InvoicePayment.ACCOUNT.equals(source)) {
            account = accounts.resolve(storeId, accountId, prefix + "accountId");
            p.setAccountId(account.getId());
        } else {
            partners.requirePayer(storeId, partnerId, source, prefix + "partnerId");
            p.setPartnerId(partnerId);
        }
        em.persist(p);
        if (account != null) {
            LedgerService.Entry entry = LedgerService.Entry.of(storeId, account.getId(), currency, "PURCHASE_PAYMENT", amount.negate())
                    .ref("PURCHASE_INVOICE", invoice.getId()).group(p.getId()).note("دفع " + label);
            ledger.record(rate == null ? entry : entry.rate(rate.id(), rate.sellRate()));
        }
    }

    /** Reverses the box movements of the invoice: all of them, or only those of one payment. */
    private void reverseLedger(UUID storeId, UUID invoiceId, UUID paymentId, String reason) {
        @SuppressWarnings("unchecked")
        List<Object[]> entries = em.createNativeQuery("""
                select account_id, wallet_currency, amount, exchange_rate_id, exchange_rate_snapshot from cash_movements
                where store_id = :store and reference_type = 'PURCHASE_INVOICE' and reference_id = :invoice
                  and (cast(:payment as uuid) is null or exchange_group = cast(:payment as uuid))""")
                .setParameter("store", storeId).setParameter("invoice", invoiceId).setParameter("payment", paymentId)
                .getResultList();
        for (Object[] e : entries) {
            LedgerService.Entry entry = LedgerService.Entry.of(storeId, (UUID) e[0], (String) e[1], "ADJUSTMENT",
                    ((BigDecimal) e[2]).negate()).ref("PURCHASE_INVOICE", invoiceId).group(paymentId).note(reason);
            ledger.record(e[3] == null ? entry : entry.rate((UUID) e[3], (BigDecimal) e[4]));
        }
    }

    // ---- Landed cost and stock -------------------------------------------------------

    /** Spreads the costs over the lines by value and sets each line's landed unit cost (before arrival). */
    private static void reprice(List<InvoiceItem> items, BigDecimal costs) {
        List<BigDecimal> shares = allocate(items.stream().map(PurchaseInvoiceService::lineValue).toList(), costs);
        for (int i = 0; i < items.size(); i++) {
            InvoiceItem item = items.get(i);
            item.setExtraCostUsd(shares.get(i));
            item.setLandedUnitCost(landed(item.getUnitPriceUsd(), shares.get(i), item.getQuantity()));
        }
    }

    private void openLots(PurchaseInvoice invoice, List<InvoiceItem> items) {
        Instant now = Instant.now();
        for (InvoiceItem item : items) {
            InventoryLot lot = new InventoryLot();
            lot.setStoreId(invoice.getStoreId());
            lot.setProductId(item.getProductId());
            lot.setInvoiceItemId(item.getId());
            lot.setQtyIn(item.getQuantity());
            lot.setQtyLeft(item.getQuantity());
            lot.setUnitCost(item.getLandedUnitCost());
            lot.setReceivedAt(now);
            em.persist(lot);
        }
        invoice.setStatus(RECEIVED);
        invoice.setReceivedAt(now);
    }

    /** A late cost over the units still in stock, by their value; each such lot's unit cost goes up. */
    private void spreadOverStock(UUID invoiceId, List<Object[]> lots, BigDecimal extra) {
        List<Object[]> stocked = lots.stream().filter(l -> ((Number) l[1]).intValue() > 0).toList();
        Map<UUID, InvoiceItem> items = itemRepository.findByInvoiceIdOrderByPositionAscIdAsc(invoiceId).stream()
                .collect(Collectors.toMap(InvoiceItem::getId, i -> i));
        List<BigDecimal> values = stocked.stream()
                .map(l -> items.get((UUID) l[3]).getUnitPriceUsd().multiply(BigDecimal.valueOf(((Number) l[1]).intValue())))
                .toList();
        List<BigDecimal> shares = allocate(values, extra);
        for (int i = 0; i < stocked.size(); i++) {
            Object[] l = stocked.get(i);
            int left = ((Number) l[1]).intValue();
            BigDecimal unitCost = ((BigDecimal) l[2])
                    .add(shares.get(i).divide(BigDecimal.valueOf(left), 6, RoundingMode.HALF_EVEN));
            em.createNativeQuery("update inventory_lots set unit_cost = :cost, version = version + 1 where id = :id")
                    .setParameter("cost", unitCost).setParameter("id", l[0]).executeUpdate();
            InvoiceItem item = items.get((UUID) l[3]);
            item.setExtraCostUsd(item.getExtraCostUsd().add(shares.get(i)));
            item.setLandedUnitCost(unitCost);
        }
    }

    /** Locks the invoice's lots (no order can take from them meanwhile): [lot id, qty left, unit cost, item id]. */
    @SuppressWarnings("unchecked")
    private List<Object[]> lockLots(UUID invoiceId) {
        return em.createNativeQuery("""
                select l.id, l.qty_left, l.unit_cost, l.invoice_item_id from inventory_lots l
                join invoice_items i on i.id = l.invoice_item_id
                where i.invoice_id = :invoice order by i.position, i.id for update of l""")
                .setParameter("invoice", invoiceId).getResultList();
    }

    /** Units of this invoice no longer in the warehouse (sold, or taken by a pending order). */
    private int unitsUsed(UUID invoiceId) {
        Number n = (Number) em.createNativeQuery("""
                select coalesce(sum(l.qty_in - l.qty_left), 0) from inventory_lots l
                join invoice_items i on i.id = l.invoice_item_id where i.invoice_id = :invoice""")
                .setParameter("invoice", invoiceId).getSingleResult();
        return n.intValue();
    }

    private int unitsInStock(UUID invoiceId) {
        Number n = (Number) em.createNativeQuery("""
                select coalesce(sum(l.qty_left), 0) from inventory_lots l
                join invoice_items i on i.id = l.invoice_item_id where i.invoice_id = :invoice""")
                .setParameter("invoice", invoiceId).getSingleResult();
        return n.intValue();
    }

    /**
     * Each value's share of {@code extra}, proportional to the value. Shares are rounded to 4 decimals and the last
     * takes the rounding remainder, so they always add up to exactly {@code extra}.
     */
    static List<BigDecimal> allocate(List<BigDecimal> values, BigDecimal extra) {
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < values.size(); i++) {
            BigDecimal share;
            if (extra.signum() == 0 || total.signum() == 0) {
                share = BigDecimal.ZERO.setScale(4);
            } else if (i == values.size() - 1) {
                share = extra.subtract(allocated).setScale(4, RoundingMode.HALF_EVEN);
            } else {
                share = values.get(i).multiply(extra).divide(total, 4, RoundingMode.HALF_EVEN);
            }
            allocated = allocated.add(share);
            shares.add(share);
        }
        return shares;
    }

    private static BigDecimal landed(BigDecimal price, BigDecimal share, int quantity) {
        return price.add(share.divide(BigDecimal.valueOf(quantity), 6, RoundingMode.HALF_EVEN)).setScale(6, RoundingMode.HALF_EVEN);
    }

    // ---- Internals -------------------------------------------------------------------

    PurchaseInvoice find(UUID storeId, UUID id) {
        return invoiceRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new ResourceNotFoundException("INVOICE_NOT_FOUND", "Purchase invoice", id));
    }

    private PurchaseInvoice lock(UUID storeId, UUID id) {
        PurchaseInvoice invoice = em.find(PurchaseInvoice.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (invoice == null || !invoice.getStoreId().equals(storeId)) {
            throw new ResourceNotFoundException("INVOICE_NOT_FOUND", "Purchase invoice", id);
        }
        return invoice;
    }

    private static void requireNotVoided(PurchaseInvoice invoice) {
        if (invoice.getVoidedAt() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVOICE_ALREADY_VOIDED", "The invoice is voided");
        }
    }

    private BigDecimal costTotal(UUID invoiceId) {
        em.flush();
        Object sum = em.createNativeQuery("select coalesce(sum(amount_usd), 0) from purchase_invoice_costs where invoice_id = :id")
                .setParameter("id", invoiceId).getSingleResult();
        return ((BigDecimal) sum).setScale(4, RoundingMode.HALF_EVEN);
    }

    private void loadProducts(UUID storeId, List<InvoiceItemRequest> items) {
        Set<UUID> ids = items.stream().map(InvoiceItemRequest::productId).collect(Collectors.toSet());
        Set<UUID> found = productRepository.findAllById(ids).stream()
                .filter(p -> p.getStoreId().equals(storeId) && p.getDeletedAt() == null)
                .map(Product::getId).collect(Collectors.toSet());
        for (int i = 0; i < items.size(); i++) {
            if (!found.contains(items.get(i).productId())) {
                throw BusinessException.onField(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                        "items[" + i + "].productId", "Product not found");
            }
        }
    }

    private static BigDecimal lineValue(InvoiceItem line) {
        return line.getUnitPriceUsd().multiply(BigDecimal.valueOf(line.getQuantity()));
    }

    private static BigDecimal goodsTotal(List<InvoiceItem> lines) {
        return lines.stream().map(PurchaseInvoiceService::lineValue).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_EVEN);
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

    private static PurchaseInvoiceResponse.Ref supplierRef(Map<UUID, Supplier> suppliers, PurchaseInvoice inv) {
        Supplier s = inv.getSupplierId() == null ? null : suppliers.get(inv.getSupplierId());
        return s == null ? null : new PurchaseInvoiceResponse.Ref(s.getId(), s.getName());
    }

    /** id → name for the given ids of a table with a name column. */
    private Map<UUID, String> names(String table, Set<UUID> ids) {
        Map<UUID, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("select id, name from " + table + " where id in (:ids)")
                .setParameter("ids", ids).getResultList();
        rows.forEach(r -> out.put((UUID) r[0], (String) r[1]));
        return out;
    }

    private PurchaseInvoiceResponse toResponse(PurchaseInvoice inv) {
        List<InvoiceItem> lines = itemRepository.findByInvoiceIdOrderByPositionAscIdAsc(inv.getId());
        Map<UUID, Product> products = new HashMap<>();
        productRepository.findAllById(lines.stream().map(InvoiceItem::getProductId).collect(Collectors.toSet()))
                .forEach(p -> products.put(p.getId(), p));
        List<PurchaseInvoiceResponse.Item> items = lines.stream().map(l -> {
            Product p = products.get(l.getProductId());
            return new PurchaseInvoiceResponse.Item(l.getId(),
                    new PurchaseInvoiceResponse.ProductRef(p.getId(), p.getName(), p.getSku()),
                    l.getQuantity(), l.getUnitPriceUsd(), l.getExtraCostUsd(), l.getLandedUnitCost(), lineValue(l));
        }).toList();

        boolean inTransit = IN_TRANSIT.equals(inv.getStatus()) && inv.getVoidedAt() == null;
        List<InvoiceCost> costRows = costRepository.findByInvoiceIdInOrderByCreatedAtAsc(List.of(inv.getId()));
        List<PurchaseInvoiceResponse.Cost> costs = costRows.stream()
                .map(c -> new PurchaseInvoiceResponse.Cost(c.getId(), c.getKind(), c.getDescription(), c.getAmount(),
                        c.getCurrency(), c.getExchangeRateSnapshot(), c.getAmountUsd(), c.isAfterReceipt(), c.getCreatedAt(),
                        inTransit))
                .toList();

        List<InvoicePayment> paymentRows = paymentRepository.findByInvoiceIdInOrderByCreatedAtAsc(List.of(inv.getId()));
        Map<UUID, String> accountNames = names("cash_accounts", paymentRows.stream().map(InvoicePayment::getAccountId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, String> partnerNames = names("partners", paymentRows.stream().map(InvoicePayment::getPartnerId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<String, BigDecimal> bySource = new HashMap<>();
        List<PurchaseInvoiceResponse.Payment> payments = paymentRows.stream().map(p -> {
            bySource.merge(p.getSource(), p.getAmountUsd(), BigDecimal::add);
            PurchaseInvoiceResponse.Ref account = p.getAccountId() == null ? null
                    : new PurchaseInvoiceResponse.Ref(p.getAccountId(), accountNames.get(p.getAccountId()));
            PurchaseInvoiceResponse.Ref partner = p.getPartnerId() == null ? null
                    : new PurchaseInvoiceResponse.Ref(p.getPartnerId(), partnerNames.get(p.getPartnerId()));
            return new PurchaseInvoiceResponse.Payment(p.getId(), p.getCostId(), p.getSource(), account, partner,
                    p.getAmount(), p.getCurrency(), p.getAmountUsd());
        }).toList();
        PurchaseInvoiceResponse.Funding funding = new PurchaseInvoiceResponse.Funding(
                bySource.getOrDefault(InvoicePayment.ACCOUNT, BigDecimal.ZERO),
                bySource.getOrDefault(InvoicePayment.OWNER, BigDecimal.ZERO),
                bySource.getOrDefault(InvoicePayment.INVESTOR, BigDecimal.ZERO),
                bySource.getOrDefault(InvoicePayment.EXTERNAL, BigDecimal.ZERO));

        BigDecimal itemsTotal = goodsTotal(lines);
        BigDecimal costsTotal = costRows.stream().map(InvoiceCost::getAmountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PurchaseInvoiceResponse(inv.getId(), inv.getInvoiceNumber(),
                supplierRef(suppliersFor(List.of(inv)), inv), inv.getInvoiceDate(), inv.getStatus(), inv.getReceivedAt(),
                itemsTotal, costsTotal, itemsTotal.add(costsTotal), funding, inv.getNotes(),
                !fileRepository.findInvoiceIdsWithFile(List.of(inv.getId())).isEmpty(), items, costs, payments,
                unitsInStock(inv.getId()), inv.getVersion(), inv.getCreatedAt(), inv.getVoidedAt(), inv.getVoidNote(),
                inv.getVoidedAt() == null && unitsUsed(inv.getId()) == 0);
    }
}
