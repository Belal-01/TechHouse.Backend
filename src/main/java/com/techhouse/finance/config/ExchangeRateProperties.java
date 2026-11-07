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
        BigDecimal maxJumpPercent
) {
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank() && apiUrl != null && !apiUrl.isBlank();
    }
}
