package com.techhouse.finance.web.dto;

import com.techhouse.finance.domain.ExchangeRate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ExchangeRateResponse(
        UUID id,
        LocalDate rateDate,
        String currencyPair,
        BigDecimal buyRate,
        BigDecimal sellRate,
        /** (buy + sell) / 2: the rate the app uses. */
        BigDecimal midRate,
        ExchangeRate.Source source,
        ExchangeRate.Status status,
        Instant sourceUpdatedAt,
        Instant updatedAt
) {
    public static ExchangeRateResponse from(ExchangeRate r) {
        return new ExchangeRateResponse(r.getId(), r.getRateDate(), r.getCurrencyPair(), r.getBuyRate(), r.getSellRate(),
                r.getMidRate(), r.getSource(), r.getStatus(), r.getSourceUpdatedAt(), r.getUpdatedAt());
    }
}
