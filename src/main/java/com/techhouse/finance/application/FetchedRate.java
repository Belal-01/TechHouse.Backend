package com.techhouse.finance.application;

import java.math.BigDecimal;
import java.time.Instant;

/** A USD rate as the provider reported it. {@code providerRemaining} = calls left this month, if the provider says. */
public record FetchedRate(BigDecimal buy, BigDecimal sell, Instant sourceUpdatedAt, Integer providerRemaining) {}
