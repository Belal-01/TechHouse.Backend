package com.techhouse.finance;

import com.sun.net.httpserver.HttpServer;
import com.techhouse.identity.domain.Store;
import com.techhouse.identity.domain.StoreMember;
import com.techhouse.identity.domain.User;
import com.techhouse.identity.persistence.StoreMemberRepository;
import com.techhouse.identity.persistence.StoreRepository;
import com.techhouse.identity.persistence.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exchange rates end to end, against a local stand-in for the SPToday API (the real quota is never used). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExchangeRateApiTests {

    private static final UUID STORE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String EMAIL = "fx-test-" + RUN + "@example.com";

    // --- stand-in provider
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicReference<String> LAST_KEY = new AtomicReference<>();
    private static final HttpServer PROVIDER;

    static {
        try {
            PROVIDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        PROVIDER.createContext("/api/v1/overview", exchange -> {
            HITS.incrementAndGet();
            LAST_KEY.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            byte[] body = BODY.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-RateLimit-Remaining", "77");
            exchange.sendResponseHeaders(STATUS.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        PROVIDER.start();
    }

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.exchange-rate.api-url", () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort() + "/api/v1");
        registry.add("app.exchange-rate.api-key", () -> "test-key");
        registry.add("app.exchange-rate.fetch-on-startup", () -> "false");
        registry.add("app.exchange-rate.retry-backoff", () -> "0s");
        registry.add("app.exchange-rate.max-monthly-calls", () -> "100000");
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop(0);
    }

    static String overview(int buy, int sell) {
        return """
                {"ok": true, "data": {"rates": [
                  {"code": "EUR", "cities": {"damascus": {"buy": 1, "sell": 2}}},
                  {"code": "USD", "updated_at": "2026-11-08T00:11:36+03:00",
                   "cities": {"damascus": {"buy": %d, "sell": %d}, "aleppo": {"buy": 1, "sell": 1}}}
                ]}}""".formatted(buy, sell);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired StoreMemberRepository storeMemberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private String token;

    private record Reply(int status, JsonNode body) {
        String code() {
            return body.path("code").asString();
        }

        JsonNode data() {
            return body.path("data");
        }
    }

    private Reply call(String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/finance/exchange-rates" + path))
                .header("Authorization", "Bearer " + token)
                .header("X-Store-Id", STORE.toString());
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body().isEmpty() ? json.createObjectNode() : json.readTree(res.body()));
    }

    private int used() throws Exception {
        return call("GET", "/current", null).data().path("apiUsage").path("used").asInt();
    }

    private static BigDecimal dec(JsonNode node) {
        return node.decimalValue();
    }

    @Test
    void exchangeRateFlow() throws Exception {
        jdbc.update("delete from exchange_rates where store_id = ?", STORE);
        Store store = storeRepository.findById(STORE).orElseThrow();
        User user = new User();
        user.setEmail(EMAIL);
        user.setPasswordHash(passwordEncoder.encode("secret-pass"));
        user.setDisplayName("FX Test");
        user.setRole("OWNER");
        user = userRepository.save(user);
        StoreMember member = new StoreMember();
        member.setStore(store);
        member.setUser(user);
        member.setRole("OWNER");
        storeMemberRepository.save(member);
        token = json.readTree(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                                Map.of("email", EMAIL, "password", "secret-pass")))).build(),
                HttpResponse.BodyHandlers.ofString()).body()).path("data").path("accessToken").asString();

        // --- nothing yet: no rate, stale
        JsonNode empty = call("GET", "/current", null).data();
        assertTrue(empty.path("current").isMissingNode() || empty.path("current").isNull());
        assertTrue(empty.path("stale").asBoolean());
        assertTrue(empty.path("apiUsage").path("configured").asBoolean());

        // --- refresh: Damascus USD, mid = (14450 + 14525) / 2
        BODY.set(overview(14450, 14525));
        int usedBefore = used();
        Reply refreshed = call("POST", "/refresh", null);
        assertEquals(200, refreshed.status(), refreshed.body().toString());
        JsonNode current = refreshed.data().path("current");
        assertEquals(0, new BigDecimal("14487.5").compareTo(dec(current.path("midRate"))));
        assertEquals(0, new BigDecimal("14450").compareTo(dec(current.path("buyRate"))));
        assertEquals("API", current.path("source").asString());
        assertEquals("ACTIVE", current.path("status").asString());
        assertFalse(refreshed.data().path("stale").asBoolean());
        assertEquals("test-key", LAST_KEY.get());
        assertEquals(usedBefore + 1, refreshed.data().path("apiUsage").path("used").asInt());
        assertEquals(77, refreshed.data().path("apiUsage").path("providerRemaining").asInt());
        UUID todayId = UUID.fromString(current.path("id").asString());

        // a small move updates today's row in place
        BODY.set(overview(14500, 14600));
        JsonNode moved = call("POST", "/refresh", null).data().path("current");
        assertEquals(todayId.toString(), moved.path("id").asString());
        assertEquals(0, new BigDecimal("14550").compareTo(dec(moved.path("midRate"))));

        // --- a jump over 20% waits for review; the rate in use does not change
        BODY.set(overview(20000, 20100));
        JsonNode jumped = call("POST", "/refresh", null).data();
        assertEquals(0, new BigDecimal("14550").compareTo(dec(jumped.path("current").path("midRate"))));
        assertEquals("PENDING_REVIEW", jumped.path("pendingReview").path("status").asString());
        assertEquals(0, new BigDecimal("20050").compareTo(dec(jumped.path("pendingReview").path("midRate"))));
        UUID pendingId = UUID.fromString(jumped.path("pendingReview").path("id").asString());
        assertEquals("RATE_NOT_PENDING", call("POST", "/" + todayId + "/approve", null).code());

        // approving copies it onto the day's approved row (same id, so orders keep one rate per day)
        Reply approved = call("POST", "/" + pendingId + "/approve", null);
        assertEquals(200, approved.status());
        assertEquals(todayId.toString(), approved.data().path("id").asString());
        assertEquals(0, new BigDecimal("20050").compareTo(dec(approved.data().path("midRate"))));
        JsonNode afterApprove = call("GET", "/current", null).data();
        assertTrue(afterApprove.path("pendingReview").isMissingNode() || afterApprove.path("pendingReview").isNull());
        assertEquals(404, call("POST", "/" + pendingId + "/approve", null).status());

        // --- a jump that settles back within range clears the pending row by itself
        BODY.set(overview(30000, 30000));
        assertEquals("PENDING_REVIEW", call("POST", "/refresh", null).data().path("pendingReview").path("status").asString());
        BODY.set(overview(20100, 20200));
        JsonNode settled = call("POST", "/refresh", null).data();
        assertTrue(settled.path("pendingReview").isMissingNode() || settled.path("pendingReview").isNull());
        assertEquals(0, new BigDecimal("20150").compareTo(dec(settled.path("current").path("midRate"))));

        // --- manual rate: validated, replaces today's rate, and the schedule leaves it alone
        assertEquals("RATE_SELL_BELOW_BUY", call("PUT", "/today", Map.of("buyRate", 15000, "sellRate", 14000)).code());
        assertEquals("VALIDATION_ERROR", call("PUT", "/today", Map.of("buyRate", 0, "sellRate", 14000)).code());
        Reply manual = call("PUT", "/today", Map.of("buyRate", 14800, "sellRate", 14900));
        assertEquals(200, manual.status());
        assertEquals("MANUAL", manual.data().path("source").asString());
        assertEquals(0, new BigDecimal("14850").compareTo(dec(manual.data().path("midRate"))));
        assertEquals(todayId.toString(), manual.data().path("id").asString());

        // --- provider down: 3 attempts, a clear error, and the saved rate stays in use
        STATUS.set(500);
        int hits = HITS.get();
        int before = used();
        Reply failed = call("POST", "/refresh", null);
        assertEquals(503, failed.status());
        assertEquals("RATE_FETCH_FAILED", failed.code());
        assertEquals(hits + 3, HITS.get());
        assertEquals(before + 3, used());
        assertEquals(0, new BigDecimal("14850").compareTo(dec(call("GET", "/current", null).data().path("current").path("midRate"))));
        STATUS.set(200);

        // provider says ok=false / no USD for the city
        BODY.set("{\"ok\": false, \"error\": {\"code\": \"INVALID_KEY\"}}");
        assertEquals("RATE_FETCH_FAILED", call("POST", "/refresh", null).code());

        // --- quota guard: no call is made once the monthly limit is reached
        jdbc.update("update api_usage set call_count = 100000 where provider = 'sp_today'");
        hits = HITS.get();
        Reply quota = call("POST", "/refresh", null);
        assertEquals(429, quota.status());
        assertEquals("RATE_QUOTA_EXHAUSTED", quota.code());
        assertEquals(hits, HITS.get());
        jdbc.update("update api_usage set call_count = ? where provider = 'sp_today'", before + 3 + 3);

        // --- history (newest first) and store isolation
        Reply history = call("GET", "?days=7", null);
        assertEquals(1, history.data().size());
        assertEquals("MANUAL", history.data().get(0).path("source").asString());

        jdbc.update("delete from exchange_rates where store_id = ?", STORE);
        jdbc.update("delete from store_members where user_id = ?", user.getId());
        jdbc.update("delete from users where id = ?", user.getId());
    }
}
