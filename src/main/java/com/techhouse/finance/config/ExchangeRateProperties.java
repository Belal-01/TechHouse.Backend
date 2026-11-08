package com.techhouse.finance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/** {@code app.exchange-rate.*}: the SPToday API and the safeguards around it. */
@ConfigurationProperties("app.exchange-rate")
public record ExchangeRateProperties(
        String apiUrl,
        String apiKey,
        int maxMonthlyCalls,
        String cron,
        boolean fetchOnStartup,
        Duration retryBackoff,
        BigDecimal maxJumpPercent,
        /**
         * The provider's figures are divided by this to get new Syrian pounds (new SYP = old / 100).
         * SPToday still quotes old pounds; set it to 1 if it switches to the new pound.
         */
        BigDecimal providerSypDivisor
) {
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank() && apiUrl != null && !apiUrl.isBlank();
    }
}
