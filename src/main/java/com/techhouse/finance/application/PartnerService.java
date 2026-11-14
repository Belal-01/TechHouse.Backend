package com.techhouse.finance.application;

import com.techhouse.finance.domain.Partner;
import com.techhouse.finance.web.dto.PartnerDtos;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The people who put their own money into invoices: the company owners, and investors who fund specific invoices.
 * Their money never passes through a box, so it is not a cash movement; it is recorded on the invoice payments.
 */
@Service
@RequiredArgsConstructor
public class PartnerService {

    private final EntityManager em;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<PartnerDtos.PartnerResponse> list(UUID storeId) {
        record Funded(BigDecimal usd, int invoices) {}
        Map<UUID, Funded> funded = new HashMap<>();
        jdbc.query("""
                select p.partner_id, sum(p.amount_usd), count(distinct p.invoice_id)
                from purchase_invoice_payments p join purchase_invoices i on i.id = p.invoice_id
                where i.store_id = ? and i.voided_at is null and p.partner_id is not null
                group by p.partner_id""",
                rs -> {
                    funded.put(rs.getObject(1, UUID.class), new Funded(rs.getBigDecimal(2), rs.getInt(3)));
                }, storeId);
        Map<UUID, UUID> advanceProfiles = new HashMap<>();
        jdbc.query("select partner_id, id from staff_people where store_id = ? and partner_id is not null",
                rs -> { advanceProfiles.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)); }, storeId);
        return em.createQuery("select p from Partner p where p.storeId = :store order by p.kind desc, p.name", Partner.class)
                .setParameter("store", storeId).getResultList().stream()
                .map(p -> {
                    Funded f = funded.getOrDefault(p.getId(), new Funded(BigDecimal.ZERO, 0));
                    return new PartnerDtos.PartnerResponse(p.getId(), p.getName(), p.getKind(),
                            p.getProfitPercent().stripTrailingZeros(), p.getPhone(), p.getNotes(), p.isActive(),
                            f.usd().setScale(2, RoundingMode.HALF_UP), f.invoices(), p.getVersion(), advanceProfiles.get(p.getId()));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public PartnerDtos.PartnerResponse get(UUID storeId, UUID id) {
        return list(storeId).stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("PARTNER_NOT_FOUND", "Partner", id));
    }

    @Transactional
    public PartnerDtos.PartnerResponse create(UUID storeId, PartnerDtos.PartnerRequest r) {
        if (r.kind() == null) {
            throw BusinessException.onField(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "kind", "Choose owner or investor");
        }
        String name = r.name().trim();
        requireUniqueName(storeId, name, null);
        Partner p = new Partner();
        p.setStoreId(storeId);
        p.setKind(r.kind());
        apply(p, r, name);
        em.persist(p);
        em.flush();
        int linked = jdbc.update("""
                update staff_people set partner_id = ?
                where store_id = ? and partner_id is null and lower(name) = lower(?)
                  and nullif(regexp_replace(phone, '[^0-9]', '', 'g'), '') =
                      nullif(regexp_replace(cast(? as text), '[^0-9]', '', 'g'), '')""",
                p.getId(), storeId, name, p.getPhone());
        if (linked == 0) jdbc.update("insert into staff_people (id, store_id, partner_id) values (?, ?, ?)", p.getId(), storeId, p.getId());
        return get(storeId, p.getId());
    }

    @Transactional
    public PartnerDtos.PartnerResponse update(UUID storeId, UUID id, PartnerDtos.PartnerRequest r) {
        Partner p = find(storeId, id);
        if (r.version() == null || !r.version().equals(p.getVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "The record was modified by someone else");
        }
        String name = r.name().trim();
        requireUniqueName(storeId, name, id);
        apply(p, r, name);
        em.flush();
        return get(storeId, id);
    }

    /**
     * The partner paying an invoice part: must exist, be active and be of the kind the payment source says
     * (an OWNER payment from an owner, an INVESTOR payment from an investor).
     */
    @Transactional(readOnly = true)
    public Partner requirePayer(UUID storeId, UUID id, String kind, String field) {
        if (id == null) {
            throw BusinessException.onField(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field, "Choose who paid");
        }
        Partner p = em.find(Partner.class, id);
        if (p == null || !p.getStoreId().equals(storeId)) {
            throw BusinessException.onField(HttpStatus.NOT_FOUND, "PARTNER_NOT_FOUND", field, "Partner not found");
        }
        if (!p.getKind().equals(kind)) {
            throw BusinessException.onField(HttpStatus.CONFLICT, "PARTNER_KIND_MISMATCH", field,
                    "This person is not an " + kind.toLowerCase());
        }
        if (!p.isActive()) {
            throw BusinessException.onField(HttpStatus.CONFLICT, "PARTNER_INACTIVE", field, "This person is inactive");
        }
        return p;
    }

    private Partner find(UUID storeId, UUID id) {
        Partner p = em.find(Partner.class, id);
        if (p == null || !p.getStoreId().equals(storeId)) throw new ResourceNotFoundException("PARTNER_NOT_FOUND", "Partner", id);
        return p;
    }

    private void requireUniqueName(UUID storeId, String name, UUID exceptId) {
        Long n = jdbc.queryForObject("select count(*) from partners where store_id = ? and lower(name) = lower(?) and id <> ?",
                Long.class, storeId, name, exceptId == null ? new UUID(0, 0) : exceptId);
        if (n != null && n > 0) {
            throw BusinessException.onField(HttpStatus.CONFLICT, "PARTNER_NAME_EXISTS", "name", "A partner with this name exists");
        }
    }

    private static void apply(Partner p, PartnerDtos.PartnerRequest r, String name) {
        p.setName(name);
        p.setProfitPercent(r.profitPercent().setScale(2, RoundingMode.HALF_UP));
        p.setPhone(blankToNull(r.phone()));
        p.setNotes(blankToNull(r.notes()));
        if (r.active() != null) p.setActive(r.active());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
