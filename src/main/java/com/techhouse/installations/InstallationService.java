package com.techhouse.installations;

import com.techhouse.finance.application.CashAccountService;
import com.techhouse.finance.application.LedgerService;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InstallationService {
    private final JdbcTemplate jdbc;
    private final CashAccountService accounts;
    private final LedgerService ledger;

    public record MaterialInput(@NotNull UUID id, @NotBlank @Size(max = 255) String name,
                                @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal quantity,
                                @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal costUsd,
                                @NotNull UUID accountId, @NotNull LocalDate date) {}

    public record InstallationInput(@NotNull UUID id, @NotBlank @Size(max = 255) String customer,
                                    @Size(max = 255) String technician, @NotNull LocalDate date,
                                    @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal receivedUsd,
                                    @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal materialsUsd,
                                    @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal technicianUsd,
                                    @NotNull UUID receiptAccountId, UUID technicianAccountId) {}

    public record Material(UUID id, String name, BigDecimal quantity, BigDecimal costUsd, String account,
                           LocalDate date, boolean depleted) {}
    public record Installation(UUID id, String customer, String technician, LocalDate date, BigDecimal receivedUsd,
                               BigDecimal materialsUsd, BigDecimal technicianUsd, BigDecimal profitUsd,
                               String receiptAccount, String technicianAccount) {}
    public record Overview(List<Material> materials, List<Installation> installations, BigDecimal costUsd,
                           BigDecimal coveredUsd, BigDecimal uncoveredUsd) {}

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Overview overview(UUID storeId) {
        List<Material> materials = jdbc.query("""
                select m.id, m.name, m.quantity, m.cost_usd, a.name, m.purchased_on, m.depleted_at is not null
                from installation_materials m join cash_accounts a on a.id = m.account_id
                where m.store_id = ? order by m.purchased_on desc, m.id""",
                (rs, row) -> new Material(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getString(5), rs.getObject(6, LocalDate.class), rs.getBoolean(7)), storeId);
        List<Installation> installations = jdbc.query("""
                select i.id, i.customer, i.technician, i.installed_on, i.received_usd, i.materials_usd, i.technician_usd,
                       i.received_usd - i.materials_usd - i.technician_usd, receipt.name, technician.name
                from charging_installations i join cash_accounts receipt on receipt.id = i.receipt_account_id
                left join cash_accounts technician on technician.id = i.technician_account_id
                where i.store_id = ? order by i.installed_on desc, i.id""",
                (rs, row) -> new Installation(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                        rs.getBigDecimal(8), rs.getString(9), rs.getString(10)), storeId);
        BigDecimal cost = materials.stream().map(Material::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal covered = installations.stream()
                .map(i -> i.materialsUsd().min(i.receivedUsd()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).min(cost);
        return new Overview(materials, installations, cost, covered, cost.subtract(covered));
    }

    @Transactional
    public void purchase(UUID storeId, MaterialInput input) {
        UUID account = accounts.resolve(storeId, input.accountId(), "accountId").getId();
        int inserted = jdbc.update("""
                insert into installation_materials (id, store_id, name, quantity, cost_usd, account_id, purchased_on)
                values (?, ?, ?, ?, ?, ?, ?) on conflict (id) do nothing""",
                input.id(), storeId, input.name().trim(), input.quantity(), input.costUsd(), account, input.date());
        if (inserted == 1) {
            ledger.record(LedgerService.Entry.of(storeId, account, "USD", "INSTALLATION_MATERIAL_PURCHASE",
                    input.costUsd().negate()).ref("INSTALLATION_MATERIAL", input.id()).note(input.name().trim()));
        }
    }

    @Transactional
    public void markDepleted(UUID storeId, UUID id) {
        int changed = jdbc.update("""
                update installation_materials set depleted_at = coalesce(depleted_at, now())
                where store_id = ? and id = ?""", storeId, id);
        if (changed == 0) throw new ResourceNotFoundException("INSTALLATION_MATERIAL_NOT_FOUND", "Installation material", id);
    }

    @Transactional
    public void record(UUID storeId, InstallationInput input) {
        UUID receiptAccount = accounts.resolve(storeId, input.receiptAccountId(), "receiptAccountId").getId();
        UUID technicianAccount = null;
        if (input.technicianUsd().signum() > 0) {
            if (input.technicianAccountId() == null) {
                throw BusinessException.onField(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "technicianAccountId",
                        "Choose the technician payment account");
            }
            technicianAccount = accounts.resolve(storeId, input.technicianAccountId(), "technicianAccountId").getId();
        }
        String customer = input.customer().trim();
        int inserted = jdbc.update("""
                insert into charging_installations (id, store_id, customer, technician, installed_on,
                    received_usd, materials_usd, technician_usd, receipt_account_id, technician_account_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (id) do nothing""",
                input.id(), storeId, customer, input.technician() == null ? null : input.technician().trim(),
                input.date(), input.receivedUsd(), input.materialsUsd(), input.technicianUsd(), receiptAccount, technicianAccount);
        if (inserted == 0) return;
        ledger.record(LedgerService.Entry.of(storeId, receiptAccount, "USD", "INSTALLATION_RECEIPT", input.receivedUsd())
                .ref("INSTALLATION", input.id()).note(customer));
        if (technicianAccount != null) {
            ledger.record(LedgerService.Entry.of(storeId, technicianAccount, "USD", "INSTALLATION_TECHNICIAN",
                    input.technicianUsd().negate()).ref("INSTALLATION", input.id()).note(customer));
        }
    }
}
