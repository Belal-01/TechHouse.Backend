package com.techhouse.finance.application;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Counts provider calls per calendar month (UTC; the provider resets on the 1st). Writes autocommit on their own,
 * outside any business transaction, so a call is counted even when what follows fails.
 */
@Service
@RequiredArgsConstructor
public class ApiUsageService {

    public record Usage(int used, Integer providerRemaining) {}

    private final JdbcTemplate jdbc;

    public Usage current(String provider) {
        List<Usage> rows = jdbc.query("select call_count, provider_remaining from api_usage where provider = ? and month = ?",
                (rs, i) -> new Usage(rs.getInt(1), (Integer) rs.getObject(2)), provider, month());
        return rows.isEmpty() ? new Usage(0, null) : rows.getFirst();
    }

    public void countCall(String provider) {
        jdbc.update("""
                insert into api_usage (provider, month, call_count) values (?, ?, 1)
                on conflict (provider, month) do update set call_count = api_usage.call_count + 1, updated_at = now()
                """, provider, month());
    }

    public void recordRemaining(String provider, Integer remaining) {
        if (remaining == null) return;
        jdbc.update("update api_usage set provider_remaining = ?, updated_at = now() where provider = ? and month = ?",
                remaining, provider, month());
    }

    private static LocalDate month() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
    }
}
