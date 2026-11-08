package com.techhouse.finance.application;

import com.techhouse.finance.config.ExchangeRateProperties;
import com.techhouse.finance.domain.ExchangeRate;
import com.techhouse.finance.persistence.ExchangeRateRepository;
import com.techhouse.finance.web.dto.CurrentRateResponse;
import com.techhouse.finance.web.dto.ExchangeRateResponse;
import com.techhouse.finance.web.dto.ManualRateRequest;
import com.techhouse.identity.domain.Store;
import com.techhouse.identity.persistence.StoreRepository;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Keeps one USD/SYP rate per store and day. The provider is called only by the schedule or the "refresh now"
 * button, never while recording an order or expense: those read the stored rate.
 */
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);
    private static final int ATTEMPTS = 3;
    private static final String DEFAULT_CITY = "damascus";

    private final ExchangeRateRepository rates;
    private final StoreRepository stores;
    private final SpTodayRateProvider provider;
    private final ApiUsageService usage;
    private final ExchangeRateProperties properties;
    private final TransactionTemplate tx;
    private final JsonMapper json = JsonMapper.builder().build();

    // ---- Queries ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CurrentRateResponse current(UUID storeId) {
        LocalDate today = today(store(storeId));
        ExchangeRate current = first(storeId, ExchangeRate.Status.ACTIVE);
        ExchangeRate pending = first(storeId, ExchangeRate.Status.PENDING_REVIEW);
        // A pending rate older than the rate in use has been superseded; don't nag about it.
        if (pending != null && current != null && pending.getRateDate().isBefore(current.getRateDate())) {
            pending = null;
        }
        ApiUsageService.Usage used = usage.current(SpTodayRateProvider.PROVIDER);
        return new CurrentRateResponse(
                current == null ? null : ExchangeRateResponse.from(current),
                pending == null ? null : ExchangeRateResponse.from(pending),
                current == null || current.getRateDate().isBefore(today),
                today,
                new CurrentRateResponse.Usage(properties.configured(), used.used(), properties.maxMonthlyCalls(),
                        used.providerRemaining()));
    }

    @Transactional(readOnly = true)
    public List<ExchangeRateResponse> history(UUID storeId, int days) {
        LocalDate from = today(store(storeId)).minusDays(Math.clamp(days, 1, 366) - 1L);
        return rates.findByStoreIdAndCurrencyPairAndRateDateGreaterThanEqualOrderByRateDateDesc(storeId,
                ExchangeRate.USD_SYP, from).stream().map(ExchangeRateResponse::from).toList();
    }

    /**
     * The rate sales and expenses are valued at: the sell rate of the latest approved rate (even if it is not from
     * today; the header warns about that). Empty when the store has never had a rate.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<SaleRate> saleRate(UUID storeId) {
        return java.util.Optional.ofNullable(first(storeId, ExchangeRate.Status.ACTIVE))
                .map(r -> new SaleRate(r.getId(), r.getSellRate(), r.getRateDate()));
    }

    public record SaleRate(UUID id, BigDecimal sellRate, LocalDate rateDate) {}

    // ---- Commands --------------------------------------------------------------------

    /** "Refresh now": fetches and replaces today's rate, even one entered manually. */
    public CurrentRateResponse refresh(UUID storeId) {
        Store store = store(storeId);
        FetchedRate fetched = fetchWithRetry(city(store));
        tx.executeWithoutResult(status -> saveApiRate(store, fetched, true));
        return current(storeId);
    }

    /** Scheduled refresh of every Syrian store. One provider call per city; a manual rate for today is kept. */
    public void refreshAll() {
        if (!properties.configured()) {
            log.info("Exchange rate API key not set; skipping scheduled refresh");
            return;
        }
        Map<String, List<Store>> byCity = new LinkedHashMap<>();
        stores.findByActiveTrue().stream()
                .filter(s -> "SYP".equals(s.getLocalCurrency()))
                .forEach(s -> byCity.computeIfAbsent(city(s), c -> new java.util.ArrayList<>()).add(s));
        byCity.forEach((city, cityStores) -> {
            try {
                FetchedRate fetched = fetchWithRetry(city);
                cityStores.forEach(s -> tx.executeWithoutResult(status -> saveApiRate(s, fetched, false)));
            } catch (BusinessException e) {
                // Orders keep using the last saved rate; the header shows it as out of date.
                log.warn("Exchange rate refresh for {} failed: {} ({})", city, e.getCode(), e.getMessage());
            }
        });
    }

    /** True when some Syrian store has no rate for its today yet (used to decide a fetch at startup). */
    @Transactional(readOnly = true)
    public boolean anyStoreMissingToday() {
        return stores.findByActiveTrue().stream()
                .filter(s -> "SYP".equals(s.getLocalCurrency()))
                .anyMatch(s -> !rates.existsByStoreIdAndRateDateAndCurrencyPair(s.getId(), today(s), ExchangeRate.USD_SYP));
    }

    @Transactional
    public ExchangeRateResponse setManual(UUID storeId, ManualRateRequest request) {
        if (request.sellRate().compareTo(request.buyRate()) < 0) {
            throw BusinessException.onField(HttpStatus.BAD_REQUEST, "RATE_SELL_BELOW_BUY", "sellRate",
                    "Sell rate must not be below the buy rate");
        }
        Store store = store(storeId);
        ExchangeRate rate = todayRow(store, ExchangeRate.Status.ACTIVE);
        rate.setBuyRate(scale(request.buyRate()));
        rate.setSellRate(scale(request.sellRate()));
        rate.setMidRate(mid(request.buyRate(), request.sellRate()));
        rate.setSource(ExchangeRate.Source.MANUAL);
        rate.setSourceUpdatedAt(null);
        ExchangeRate saved = rates.saveAndFlush(rate);
        dropPending(store, saved.getRateDate()); // the owner has decided today's rate
        return ExchangeRateResponse.from(saved);
    }

    @Transactional
    public ExchangeRateResponse approve(UUID storeId, UUID id) {
        ExchangeRate rate = rates.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new ResourceNotFoundException("RATE_NOT_FOUND", "Exchange rate", id));
        if (rate.getStatus() != ExchangeRate.Status.PENDING_REVIEW) {
            throw new BusinessException(HttpStatus.CONFLICT, "RATE_NOT_PENDING", "This rate is not waiting for review");
        }
        // Copy the reviewed values onto the day's approved row (new or existing) and drop the pending row,
        // so a day always has at most one rate that orders can point at.
        ExchangeRate active = rates.findByStoreIdAndRateDateAndCurrencyPairAndStatus(storeId, rate.getRateDate(),
                rate.getCurrencyPair(), ExchangeRate.Status.ACTIVE).orElseGet(() -> {
                    ExchangeRate r = new ExchangeRate();
                    r.setStoreId(storeId);
                    r.setRateDate(rate.getRateDate());
                    r.setCurrencyPair(rate.getCurrencyPair());
                    r.setStatus(ExchangeRate.Status.ACTIVE);
                    return r;
                });
        active.setBuyRate(rate.getBuyRate());
        active.setSellRate(rate.getSellRate());
        active.setMidRate(rate.getMidRate());
        active.setSource(rate.getSource());
        active.setSourceUpdatedAt(rate.getSourceUpdatedAt());
        rates.delete(rate);
        rates.flush();
        return ExchangeRateResponse.from(rates.saveAndFlush(active));
    }

    // ---- Internals -------------------------------------------------------------------

    /** Up to 3 attempts, each one counted against the monthly quota before it is made. No DB transaction is held. */
    private FetchedRate fetchWithRetry(String city) {
        if (!properties.configured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "RATE_API_NOT_CONFIGURED",
                    "No exchange rate API key is configured");
        }
        RateFetchException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            ApiUsageService.Usage used = usage.current(SpTodayRateProvider.PROVIDER);
            if (used.used() >= properties.maxMonthlyCalls()
                    || (used.providerRemaining() != null && used.providerRemaining() <= 0)) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_QUOTA_EXHAUSTED",
                        "Monthly exchange rate API quota reached (" + used.used() + ")");
            }
            usage.countCall(SpTodayRateProvider.PROVIDER);
            try {
                FetchedRate fetched = provider.fetchUsd(city);
                usage.recordRemaining(SpTodayRateProvider.PROVIDER, fetched.providerRemaining());
                return fetched;
            } catch (RateFetchException e) {
                last = e;
                log.warn("Exchange rate fetch attempt {}/{} failed: {}", attempt, ATTEMPTS, e.getMessage());
                if (attempt < ATTEMPTS) sleep(properties.retryBackoff().multipliedBy(attempt).toMillis());
            }
        }
        throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "RATE_FETCH_FAILED",
                "Could not fetch the exchange rate: " + (last == null ? "unknown" : last.getMessage()));
    }

    private void saveApiRate(Store store, FetchedRate fetched, boolean replaceManual) {
        LocalDate today = today(store);
        ExchangeRate active = rates.findByStoreIdAndRateDateAndCurrencyPairAndStatus(store.getId(), today,
                ExchangeRate.USD_SYP, ExchangeRate.Status.ACTIVE).orElse(null);
        if (active != null && active.getSource() == ExchangeRate.Source.MANUAL && !replaceManual) {
            return;
        }
        BigDecimal mid = mid(fetched.buy(), fetched.sell());
        ExchangeRate inUse = first(store.getId(), ExchangeRate.Status.ACTIVE);
        // Sales use the sell rate, so that is the one guarded against sudden jumps.
        boolean jumped = inUse != null && isJump(inUse.getSellRate(), scale(fetched.sell()));

        // A jump goes to the day's pending row; the rate in use stays untouched until someone approves it.
        ExchangeRate row = jumped ? todayRow(store, ExchangeRate.Status.PENDING_REVIEW)
                : active != null ? active : todayRow(store, ExchangeRate.Status.ACTIVE);
        if (jumped) {
            log.warn("Sell rate for store {} jumped from {} to {}; waiting for review", store.getId(),
                    inUse.getSellRate(), fetched.sell());
        }
        row.setBuyRate(scale(fetched.buy()));
        row.setSellRate(scale(fetched.sell()));
        row.setMidRate(mid);
        row.setSource(ExchangeRate.Source.API);
        row.setSourceUpdatedAt(fetched.sourceUpdatedAt());
        rates.saveAndFlush(row);
        if (!jumped) {
            dropPending(store, today); // back within range: the earlier jump no longer needs a decision
        }
    }

    private void dropPending(Store store, LocalDate date) {
        rates.findByStoreIdAndRateDateAndCurrencyPairAndStatus(store.getId(), date, ExchangeRate.USD_SYP,
                ExchangeRate.Status.PENDING_REVIEW).ifPresent(p -> {
            rates.delete(p);
            rates.flush();
        });
    }

    private boolean isJump(BigDecimal from, BigDecimal to) {
        BigDecimal change = to.subtract(from).abs().multiply(BigDecimal.valueOf(100)).divide(from, 4, RoundingMode.HALF_UP);
        return change.compareTo(properties.maxJumpPercent()) > 0;
    }

    /** Today's row with this status, or a new unsaved one. */
    private ExchangeRate todayRow(Store store, ExchangeRate.Status status) {
        LocalDate today = today(store);
        return rates.findByStoreIdAndRateDateAndCurrencyPairAndStatus(store.getId(), today, ExchangeRate.USD_SYP, status)
                .orElseGet(() -> {
                    ExchangeRate r = new ExchangeRate();
                    r.setStoreId(store.getId());
                    r.setRateDate(today);
                    r.setCurrencyPair(ExchangeRate.USD_SYP);
                    r.setStatus(status);
                    return r;
                });
    }

    private ExchangeRate first(UUID storeId, ExchangeRate.Status status) {
        return rates.latest(storeId, ExchangeRate.USD_SYP, status, PageRequest.of(0, 1)).stream().findFirst().orElse(null);
    }

    private Store store(UUID storeId) {
        return stores.findById(storeId).orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "Store", storeId));
    }

    private static LocalDate today(Store store) {
        return LocalDate.now(ZoneId.of(store.getTimezone()));
    }

    private String city(Store store) {
        try {
            String city = json.readTree(store.getRateApiConfig()).path("city").asString("");
            return city.isBlank() ? DEFAULT_CITY : city;
        } catch (Exception e) {
            return DEFAULT_CITY;
        }
    }

    static BigDecimal mid(BigDecimal buy, BigDecimal sell) {
        return buy.add(sell).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
