package com.techhouse.sales;

import com.techhouse.identity.domain.Store;
import com.techhouse.identity.domain.StoreMember;
import com.techhouse.identity.domain.User;
import com.techhouse.identity.persistence.StoreMemberRepository;
import com.techhouse.identity.persistence.StoreRepository;
import com.techhouse.identity.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The worked example of spec §12, end to end through the API, on a store of its own:
 * rate 14,500 SYP/$ (sell), start 1000$ + 500,000 SYP, two invoices, one SYP order delivered, one USD order returned.
 * Then the invariant: change in net worth == net profit (67.59$).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderLifecycleTests {

    private static final BigDecimal RATE = new BigDecimal("14500");

    @Value("${local.server.port}")
    int port;

    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired StoreMemberRepository storeMemberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private UUID store;
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
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin" + path))
                .header("Authorization", "Bearer " + token)
                .header("X-Store-Id", store.toString());
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body().isEmpty() ? json.createObjectNode() : json.readTree(res.body()));
    }

    private BigDecimal wallet(String currency) {
        return jdbc.queryForObject("select coalesce(sum(amount), 0) from cash_movements where store_id = ? and wallet_currency = ?",
                BigDecimal.class, store, currency);
    }

    private JsonNode stockOf(UUID product) throws Exception {
        for (JsonNode row : call("GET", "/inventory/stock", null).data()) {
            if (row.path("productId").asString().equals(product.toString())) return row;
        }
        throw new AssertionError("product missing from stock list");
    }

    private static Map<String, Object> order(String currency, UUID product, int qty, String price, String deliveryCost,
                                             UUID company) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerName", "زبون " + qty);
        m.put("customerPhone", "0999" + qty);
        m.put("currency", currency);
        m.put("items", List.of(Map.of("productId", product, "quantity", qty, "unitPrice", new BigDecimal(price))));
        m.put("deliveryCost", new BigDecimal(deliveryCost));
        m.put("deliveryCostCurrency", "SYP");
        m.put("deliveryCompanyId", company);
        return m;
    }

    private static void assertMoney(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue().setScale(2, RoundingMode.HALF_UP)),
                () -> "expected " + expected + " but was " + actual);
    }

    /** Net worth in USD: both wallets (SYP at the fixed rate) + stock in the warehouse and on the way, at cost. */
    private BigDecimal netWorth() {
        BigDecimal stock = jdbc.queryForObject("select coalesce(sum(qty_left * unit_cost), 0) from inventory_lots where store_id = ?",
                BigDecimal.class, store);
        BigDecimal inTransit = jdbc.queryForObject("""
                select coalesce(sum(a.quantity * a.unit_cost), 0) from order_item_allocations a
                join order_items i on i.id = a.order_item_id join orders o on o.id = i.order_id
                where o.store_id = ? and o.status = 'PENDING'""", BigDecimal.class, store);
        return wallet("USD").add(wallet("SYP").divide(RATE, 10, RoundingMode.HALF_UP)).add(stock).add(inTransit);
    }

    /** Net profit (spec §4): delivered revenue − their FIFO cost − expenses that were not voided. */
    private BigDecimal netProfit() {
        BigDecimal revenue = jdbc.queryForObject("""
                select coalesce(sum(case when currency = 'USD' then items_total + delivery_fee_charged
                                    else (items_total + delivery_fee_charged) / exchange_rate_snapshot end), 0)
                from orders where store_id = ? and status = 'DELIVERED'""", BigDecimal.class, store);
        BigDecimal cogs = jdbc.queryForObject("""
                select coalesce(sum(a.quantity * a.unit_cost), 0) from order_item_allocations a
                join order_items i on i.id = a.order_item_id join orders o on o.id = i.order_id
                where o.store_id = ? and o.status = 'DELIVERED'""", BigDecimal.class, store);
        BigDecimal expenses = jdbc.queryForObject(
                "select coalesce(sum(amount_usd), 0) from expenses where store_id = ? and voided_at is null",
                BigDecimal.class, store);
        return revenue.subtract(cogs).subtract(expenses);
    }

    @Test
    void specExampleKeepsTheBooksBalanced() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        store = UUID.randomUUID();
        jdbc.update("""
                insert into stores (id, name, country_code, local_currency, timezone, rate_api_config)
                values (?, ?, 'SY', 'SYP', 'Asia/Damascus', '{"provider": "sp_today", "city": "damascus"}')""",
                store, "Order test " + run);
        Store s = storeRepository.findById(store).orElseThrow();
        User user = new User();
        user.setEmail("orders-" + run + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("secret-pass"));
        user.setDisplayName("Orders Test");
        user.setRole("OWNER");
        user = userRepository.save(user);
        StoreMember member = new StoreMember();
        member.setStore(s);
        member.setUser(user);
        member.setRole("OWNER");
        storeMemberRepository.save(member);
        token = json.readTree(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                                Map.of("email", user.getEmail(), "password", "secret-pass")))).build(),
                HttpResponse.BodyHandlers.ofString()).body()).path("data").path("accessToken").asString();

        // --- no rate yet: an SYP order is refused before anything happens
        assertEquals("EXCHANGE_RATE_REQUIRED",
                call("POST", "/sales/orders", order("SYP", UUID.randomUUID(), 1, "1", "0", null)).code());

        // --- starting point: 1000$ + 500,000 SYP, rate 14,500 (sell)
        jdbc.update("insert into cash_movements (store_id, wallet_currency, movement_type, amount) values (?, 'USD', 'CAPITAL_IN', 1000)", store);
        jdbc.update("insert into cash_movements (store_id, wallet_currency, movement_type, amount) values (?, 'SYP', 'CAPITAL_IN', 500000)", store);
        jdbc.update("""
                insert into exchange_rates (store_id, rate_date, currency_pair, buy_rate, sell_rate, mid_rate, source, status)
                values (?, (now() at time zone 'Asia/Damascus')::date, 'USD/SYP', 14400, 14500, 14450, 'MANUAL', 'ACTIVE')""", store);
        BigDecimal startWorth = netWorth();

        Reply product = call("POST", "/catalog/products", Map.of("name", "A", "sellingPrice", 12, "active", true));
        assertEquals(201, product.status(), product.body().toString());
        UUID a = UUID.fromString(product.data().path("id").asString());
        Reply company = call("POST", "/sales/delivery-companies", Map.of("name", "Fast " + run));
        assertEquals(201, company.status());
        UUID companyId = UUID.fromString(company.data().path("id").asString());

        // --- 1 & 2: invoices → lots at 6$ and 7$
        for (String price : List.of("5", "6")) {
            Reply inv = call("POST", "/procurement/invoices", Map.of("invoiceDate", "2026-11-01", "shippingCostUsd", 10,
                    "paidFrom", "INCOME", "items", List.of(Map.of("productId", a, "quantity", 10, "unitPriceUsd", new BigDecimal(price)))));
            assertEquals(201, inv.status(), inv.body().toString());
        }
        assertEquals(0, new BigDecimal("870").compareTo(wallet("USD")));

        // --- 3: order #1, 12 × 174,000 SYP, delivery 20,000 SYP → stock 8, SYP 480,000
        Reply o1 = call("POST", "/sales/orders", order("SYP", a, 12, "174000", "20000", companyId));
        assertEquals(201, o1.status(), o1.body().toString());
        UUID order1 = UUID.fromString(o1.data().path("id").asString());
        assertEquals("PENDING", o1.data().path("status").asString());
        assertTrue(o1.data().path("orderNumber").asString().startsWith("SO-"));
        assertEquals(0, RATE.compareTo(o1.data().path("exchangeRate").decimalValue()));
        assertMoney("74.00", o1.data().path("items").get(0).path("costUsd"));       // 10 × 6 + 2 × 7 (FIFO)
        assertEquals("Fast " + run, o1.data().path("deliveryCompany").path("name").asString());
        assertEquals(8, stockOf(a).path("available").asInt());
        assertEquals(12, stockOf(a).path("inTransit").asInt());
        assertEquals(0, new BigDecimal("480000").compareTo(wallet("SYP")));

        // --- 4: order #2, 3 × 12$, delivery 15,000 SYP → stock 5, SYP 465,000
        Reply o2 = call("POST", "/sales/orders", order("USD", a, 3, "12", "15000", companyId));
        assertEquals(201, o2.status(), o2.body().toString());
        UUID order2 = UUID.fromString(o2.data().path("id").asString());
        assertEquals(5, stockOf(a).path("available").asInt());
        assertEquals(0, new BigDecimal("465000").compareTo(wallet("SYP")));

        // not enough stock: refused on the quantity field, and nothing changes
        Reply tooMany = call("POST", "/sales/orders", order("USD", a, 6, "12", "5000", null));
        assertEquals(409, tooMany.status());
        assertEquals("INSUFFICIENT_STOCK", tooMany.code());
        assertTrue(tooMany.body().path("fieldErrors").has("items[0].quantity"));
        assertEquals(5, stockOf(a).path("available").asInt());
        assertEquals(0, new BigDecimal("465000").compareTo(wallet("SYP")));

        // --- 5: #1 delivered → SYP 2,553,000; revenue 144$, cost 74$
        Reply d1 = call("POST", "/sales/orders/" + order1 + "/deliver", Map.of("note", "استلم"));
        assertEquals(200, d1.status(), d1.body().toString());
        assertEquals(0, new BigDecimal("2553000").compareTo(wallet("SYP")));
        assertMoney("144.00", d1.data().path("money").path("revenueUsd"));
        assertMoney("74.00", d1.data().path("money").path("cogsUsd"));
        assertMoney("1.38", d1.data().path("money").path("deliveryCostUsd"));
        assertMoney("68.62", d1.data().path("money").path("profitUsd"));
        assertEquals(2, d1.data().path("history").size());
        assertEquals("استلم", d1.data().path("history").get(1).path("note").asString());

        // --- 6: #2 returned → stock back to 8; it lost its delivery fee
        Reply r2 = call("POST", "/sales/orders/" + order2 + "/return", null);
        assertEquals(200, r2.status(), r2.body().toString());
        assertEquals(8, stockOf(a).path("available").asInt());
        assertEquals(0, stockOf(a).path("inTransit").asInt());
        assertMoney("-1.03", r2.data().path("money").path("profitUsd"));
        // final states are final
        assertEquals("ORDER_NOT_PENDING", call("POST", "/sales/orders/" + order2 + "/deliver", null).code());
        assertEquals("ORDER_NOT_PENDING", call("POST", "/sales/orders/" + order1 + "/void", null).code());

        // --- the invariant: change in net worth == net profit == 67.59$
        BigDecimal profit = netProfit();
        assertEquals(0, new BigDecimal("67.59").compareTo(profit.setScale(2, RoundingMode.HALF_UP)), "net profit " + profit);
        BigDecimal change = netWorth().subtract(startWorth);
        assertTrue(change.subtract(profit).abs().compareTo(new BigDecimal("0.01")) < 0,
                "net worth changed by " + change + " but net profit is " + profit);

        // --- an order entered by mistake: voided, everything back, the books still balance
        Reply o3 = call("POST", "/sales/orders", order("SYP", a, 2, "180000", "10000", null));
        UUID order3 = UUID.fromString(o3.data().path("id").asString());
        assertEquals(6, stockOf(a).path("available").asInt());
        Reply v3 = call("POST", "/sales/orders/" + order3 + "/void", null);
        assertEquals(200, v3.status(), v3.body().toString());
        assertEquals(8, stockOf(a).path("available").asInt());
        assertEquals(0, new BigDecimal("2553000").compareTo(wallet("SYP")));
        assertEquals(1, jdbc.queryForObject("select count(*) from expenses where order_id = ? and voided_at is not null",
                Integer.class, order3));
        assertEquals(0, new BigDecimal("67.59").compareTo(netProfit().setScale(2, RoundingMode.HALF_UP)));
        assertTrue(netWorth().subtract(startWorth).subtract(netProfit()).abs().compareTo(new BigDecimal("0.01")) < 0);

        // --- listing and search
        assertEquals(1, call("GET", "/sales/orders?status=DELIVERED", null).data().path("totalElements").asInt());
        assertEquals(3, call("GET", "/sales/orders", null).data().path("totalElements").asInt());
        JsonNode found = call("GET", "/sales/orders?q=09993", null).data();
        assertEquals(1, found.path("totalElements").asInt());
        assertEquals(order2.toString(), found.path("content").get(0).path("id").asString());
        assertEquals(404, call("GET", "/sales/orders/" + UUID.randomUUID(), null).status());

        // --- delivery companies: in use → can't delete; inactive → can't be picked
        assertEquals("DELIVERY_COMPANY_IN_USE", call("DELETE", "/sales/delivery-companies/" + companyId, null).code());
        assertEquals(200, call("PUT", "/sales/delivery-companies/" + companyId,
                Map.of("name", "Fast " + run, "active", false, "version", company.data().path("version").asInt())).status());
        assertEquals("DELIVERY_COMPANY_INACTIVE", call("POST", "/sales/orders", order("USD", a, 1, "12", "0", companyId)).code());

        // The ledger can't be edited and the status history names who changed what, so the data stays
        // (as in production); only the store access is removed.
        jdbc.update("delete from store_members where user_id = ?", user.getId());
    }
}
