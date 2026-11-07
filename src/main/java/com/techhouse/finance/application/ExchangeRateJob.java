package com.techhouse.finance.application;

import com.techhouse.finance.config.ExchangeRateProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Refreshes the rate on a schedule (Damascus time), and once at startup if today's rate is missing. */
@Component
@RequiredArgsConstructor
public class ExchangeRateJob {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateJob.class);

    private final ExchangeRateService service;
    private final ExchangeRateProperties properties;

    @Scheduled(cron = "${app.exchange-rate.cron}", zone = "Asia/Damascus")
    public void scheduled() {
        run();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!properties.fetchOnStartup() || !properties.configured()) return;
        // Off the startup thread: retries may wait a few seconds.
        Thread.ofVirtual().name("exchange-rate-startup").start(() -> {
            if (service.anyStoreMissingToday()) run();
        });
    }

    private void run() {
        try {
            service.refreshAll();
        } catch (RuntimeException e) {
            log.error("Exchange rate refresh crashed", e);
        }
    }
}
