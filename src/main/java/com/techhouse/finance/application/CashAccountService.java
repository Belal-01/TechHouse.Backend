package com.techhouse.finance.application;

import com.techhouse.finance.domain.CashAccount;
import com.techhouse.finance.persistence.CashAccountRepository;
import com.techhouse.finance.web.dto.CashAccountRequest;
import com.techhouse.finance.web.dto.CashAccountResponse;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CashAccountService {

    private final CashAccountRepository repository;
    private final JdbcTemplate jdbc;

    /**
     * The account a movement goes to: the one given (must belong to the store and be active), or the default box.
     * {@code field} names the request field for error messages, e.g. "accountId".
     */
    @Transactional(readOnly = true)
    public CashAccount resolve(UUID storeId, UUID accountId, String field) {
        if (accountId == null) {
            return repository.findByStoreIdAndDefaultAccountTrue(storeId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "CASH_ACCOUNT_NOT_FOUND",
                            "The store has no default cash account"));
        }
        CashAccount account = repository.findByIdAndStoreId(accountId, storeId)
                .orElseThrow(() -> BusinessException.onField(HttpStatus.NOT_FOUND, "CASH_ACCOUNT_NOT_FOUND", field,
                        "Cash account not found"));
        if (!account.isActive()) {
            throw BusinessException.onField(HttpStatus.CONFLICT, "CASH_ACCOUNT_INACTIVE", field, "Cash account is inactive");
        }
        return account;
    }

    @Transactional(readOnly = true)
    public List<CashAccountResponse> list(UUID storeId) {
        Map<String, BigDecimal> balance = new HashMap<>();
        jdbc.query("select account_id, wallet_currency, sum(amount) from cash_movements where store_id = ? group by 1, 2",
                rs -> {
                    balance.put(rs.getString(1) + rs.getString(2), rs.getBigDecimal(3));
                }, storeId);
        return repository.findByStoreIdOrderByDefaultAccountDescNameAsc(storeId).stream()
                .map(a -> new CashAccountResponse(a.getId(), a.getName(), a.isDefaultAccount(), a.isActive(),
                        balance.getOrDefault(a.getId() + "USD", BigDecimal.ZERO),
                        balance.getOrDefault(a.getId() + "SYP", BigDecimal.ZERO), a.getVersion()))
                .toList();
    }

    @Transactional
    public CashAccountResponse create(UUID storeId, CashAccountRequest request) {
        String name = request.name().trim();
        if (repository.existsByStoreIdAndNameIgnoreCase(storeId, name)) throw nameExists();
        CashAccount account = new CashAccount();
        account.setStoreId(storeId);
        account.setName(name);
        if (request.active() != null) account.setActive(request.active());
        CashAccount saved = repository.saveAndFlush(account);
        return find(storeId, saved.getId());
    }

    @Transactional
    public CashAccountResponse update(UUID storeId, UUID id, CashAccountRequest request) {
        CashAccount account = repository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new ResourceNotFoundException("CASH_ACCOUNT_NOT_FOUND", "Cash account", id));
        if (request.version() == null || !request.version().equals(account.getVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "The record was modified by someone else");
        }
        String name = request.name().trim();
        if (repository.existsByStoreIdAndNameIgnoreCaseAndIdNot(storeId, name, id)) throw nameExists();
        if (Boolean.FALSE.equals(request.active()) && account.isDefaultAccount()) {
            throw new BusinessException(HttpStatus.CONFLICT, "CASH_ACCOUNT_DEFAULT", "The default account can't be deactivated");
        }
        account.setName(name);
        if (request.active() != null) account.setActive(request.active());
        repository.saveAndFlush(account);
        return find(storeId, id);
    }

    private CashAccountResponse find(UUID storeId, UUID id) {
        return list(storeId).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    private static BusinessException nameExists() {
        return BusinessException.onField(HttpStatus.CONFLICT, "CASH_ACCOUNT_NAME_EXISTS", "name", "Account name already exists");
    }
}
