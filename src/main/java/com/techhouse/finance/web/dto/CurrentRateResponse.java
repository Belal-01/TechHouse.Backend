package com.techhouse.finance.web.dto;

import java.time.LocalDate;

/**
 * What the header shows.
 * @param current       the rate in use (latest approved), or null if there has never been one
 * @param pendingReview a newer fetched rate that jumped too far and waits for approval, if any
 * @param stale         true when the rate in use is not from today (the store's local date)
 */
public record CurrentRateResponse(
        ExchangeRateResponse current,
        ExchangeRateResponse pendingReview,
        boolean stale,
        LocalDate today,
        Usage apiUsage
) {
    /** @param configured false when no API key is set: rates can only be entered manually */
    public record Usage(boolean configured, int used, int limit, Integer providerRemaining) {}
}
