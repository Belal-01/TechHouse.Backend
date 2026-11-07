package com.techhouse.finance.application;

import com.techhouse.finance.config.ExchangeRateProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Reads the US dollar rate of one city from SPToday's {@code /overview} endpoint. One call = one unit of quota. */
@Component
@RequiredArgsConstructor
public class SpTodayRateProvider {

    public static final String PROVIDER = "sp_today";

    private final ExchangeRateProperties properties;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public FetchedRate fetchUsd(String city) {
        String url = properties.apiUrl().replaceAll("/+$", "") + "/overview?lang=en";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("X-API-Key", properties.apiKey())
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RateFetchException("Interrupted", e);
        } catch (Exception e) {
            throw new RateFetchException("Request failed: " + e.getMessage(), e);
        }
        Integer remaining = response.headers().firstValue("X-RateLimit-Remaining").map(SpTodayRateProvider::toInt).orElse(null);
        if (response.statusCode() != 200) {
            throw new RateFetchException("HTTP " + response.statusCode());
        }
        return parse(response.body(), city, remaining);
    }

    FetchedRate parse(String body, String city, Integer remaining) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            throw new RateFetchException("Unreadable response", e);
        }
        if (!root.path("ok").asBoolean(false)) {
            throw new RateFetchException("Provider error: " + root.path("error").path("code").asString("unknown"));
        }
        for (JsonNode rate : root.path("data").path("rates")) {
            if (!"USD".equals(rate.path("code").asString(""))) continue;
            JsonNode c = rate.path("cities").path(city);
            if (c.isMissingNode() || !c.path("buy").isNumber() || !c.path("sell").isNumber()) {
                throw new RateFetchException("No USD rate for city " + URLEncoder.encode(city, StandardCharsets.UTF_8));
            }
            BigDecimal buy = c.path("buy").decimalValue();
            BigDecimal sell = c.path("sell").decimalValue();
            if (buy.signum() <= 0 || sell.compareTo(buy) < 0) {
                throw new RateFetchException("Invalid rate " + buy + "/" + sell);
            }
            Instant updated = null;
            String at = rate.path("updated_at").asString("");
            if (!at.isBlank()) {
                try {
                    updated = OffsetDateTime.parse(at).toInstant();
                } catch (Exception ignored) {
                    // the rate itself is fine; only the timestamp is unreadable
                }
            }
            return new FetchedRate(buy, sell, updated, remaining);
        }
        throw new RateFetchException("No USD rate in response");
    }

    private static Integer toInt(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
