package com.techhouse.finance.persistence;

import com.techhouse.finance.domain.ExchangeRate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, UUID> {

    Optional<ExchangeRate> findByStoreIdAndRateDateAndCurrencyPairAndStatus(UUID storeId, LocalDate rateDate,
                                                                          String currencyPair, ExchangeRate.Status status);

    boolean existsByStoreIdAndRateDateAndCurrencyPair(UUID storeId, LocalDate rateDate, String currencyPair);

    Optional<ExchangeRate> findByIdAndStoreId(UUID id, UUID storeId);

    /** Newest first; the first ACTIVE row is the rate in use. */
    @Query("""
            select r from ExchangeRate r
            where r.storeId = :storeId and r.currencyPair = :pair and r.status = :status
            order by r.rateDate desc, r.updatedAt desc
            """)
    List<ExchangeRate> latest(@Param("storeId") UUID storeId, @Param("pair") String pair,
                              @Param("status") ExchangeRate.Status status, Pageable pageable);

    List<ExchangeRate> findByStoreIdAndCurrencyPairAndRateDateGreaterThanEqualOrderByRateDateDesc(
            UUID storeId, String currencyPair, LocalDate from);
}
