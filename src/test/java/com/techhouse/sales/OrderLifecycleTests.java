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

    private BigDecimal box(UUID account, String currency) {
        return jdbc.queryForObject("select coalesce(sum(amount), 0) from cash_movements where account_id = ? and wallet_currency = ?",
                BigDecimal.class, account, currency);
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
        BigDecimal advUsd = jdbc.queryForObject("select coalesce(sum(amount), 0) from advance_entries where store_id = ? and currency = 'USD'",
                BigDecimal.class, store);
        BigDecimal advSyp = jdbc.queryForObject("select coalesce(sum(amount), 0) from advance_entries where store_id = ? and currency = 'SYP'",
                BigDecimal.class, store);
        return wallet("USD").add(wallet("SYP").divide(RATE, 10, RoundingMode.HALF_UP)).add(stock).add(inTransit)
                .add(advUsd).add(advSyp.divide(RATE, 10, RoundingMode.HALF_UP));
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
        // new stores get two boxes (default "in hand" + Sham Cash) and the default expense categories
        JsonNode boxes = call("GET", "/finance/accounts", null).data();
        assertEquals(2, boxes.size());
        UUID hand = UUID.fromString(boxes.get(0).path("id").asString());
        UUID shamCash = UUID.fromString(boxes.get(1).path("id").asString());
        assertTrue(boxes.get(0).path("isDefault").asBoolean());
        assertEquals(200, call("POST", "/finance/movements/capital", Map.of("accountId", hand, "currency", "USD", "amount", 1000)).status());
        assertEquals(200, call("POST", "/finance/movements/capital", Map.of("accountId", hand, "currency", "SYP", "amount", 500000)).status());
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
        assertEquals("VALIDATION_ERROR", call("POST", "/sales/orders/" + order1 + "/deliver", Map.of("note", "x")).code());
        Reply d1 = call("POST", "/sales/orders/" + order1 + "/deliver", Map.of("accountId", shamCash, "note", "استلم"));
        assertEquals(200, d1.status(), d1.body().toString());
        assertEquals(0, new BigDecimal("2553000").compareTo(wallet("SYP")));
        assertEquals(0, new BigDecimal("2088000").compareTo(box(shamCash, "SYP")));   // the money went to Sham Cash
        assertEquals("شام كاش", d1.data().path("paymentAccount").path("name").asString());
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
        assertEquals("ORDER_NOT_PENDING", call("POST", "/sales/orders/" + order2 + "/deliver", Map.of("accountId", hand)).code());
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

        // --- moving money between boxes and currencies changes nothing (exchange at the fixed rate)
        assertEquals(200, call("POST", "/finance/movements/transfer", Map.of("fromAccountId", shamCash, "toAccountId", hand,
                "currency", "SYP", "amount", 88000)).status());
        assertEquals("SAME_ACCOUNT", call("POST", "/finance/movements/transfer", Map.of("fromAccountId", hand, "toAccountId", hand,
                "currency", "SYP", "amount", 1)).code());
        Reply exchanged = call("POST", "/finance/movements/exchange", Map.of("fromAccountId", hand, "fromCurrency", "USD",
                "fromAmount", 100, "toAccountId", hand, "toAmount", 1450000));
        assertEquals(200, exchanged.status(), exchanged.body().toString());
        assertEquals(0, new BigDecimal("770").compareTo(box(hand, "USD")));
        assertEquals(0, new BigDecimal("2000000").compareTo(box(shamCash, "SYP")));
        assertTrue(netWorth().subtract(startWorth).subtract(netProfit()).abs().compareTo(new BigDecimal("0.01")) < 0);

        // --- a manual expense (own category), then voided: money back, profit back
        Reply category = call("POST", "/finance/expense-categories", Map.of("name", "إيجار " + run));
        assertEquals(201, category.status());
        UUID rentId = UUID.fromString(category.data().path("id").asString());
        Reply rent = call("POST", "/finance/expenses", Map.of("categoryId", rentId, "amount", 290000, "currency", "SYP",
                "accountId", hand, "expenseDate", "2026-11-08", "description", "إيجار تشرين"));
        assertEquals(201, rent.status(), rent.body().toString());
        assertMoney("20.00", rent.data().path("amountUsd"));                       // 290,000 / 14,500
        assertMoney("47.59", new tools.jackson.databind.node.DecimalNode(netProfit()));
        assertEquals("EXPENSE_CATEGORY_IN_USE", call("DELETE", "/finance/expense-categories/" + rentId, null).code());
        UUID rentExpense = UUID.fromString(rent.data().path("id").asString());
        assertEquals(200, call("POST", "/finance/expenses/" + rentExpense + "/void", Map.of("note", "خطأ")).status());
        assertEquals("EXPENSE_ALREADY_VOIDED", call("POST", "/finance/expenses/" + rentExpense + "/void", null).code());
        assertMoney("67.59", new tools.jackson.databind.node.DecimalNode(netProfit()));
        JsonNode delivery = call("GET", "/finance/expenses?categoryId=" + call("GET", "/finance/expense-categories", null).data()
                .valueStream().filter(c -> c.path("system").asBoolean() && c.path("name").asString().equals("توصيل"))
                .findFirst().orElseThrow().path("id").asString(), null).data();
        assertEquals(3, delivery.path("totalElements").asInt());                  // 2 kept + 1 voided with its order
        String keptFee = delivery.path("content").valueStream().filter(e -> e.path("voidedAt").isMissingNode())
                .findFirst().orElseThrow().path("id").asString();
        assertEquals("EXPENSE_BELONGS_TO_ORDER", call("POST", "/finance/expenses/" + keptFee + "/void", null).code());

        // --- a manager's advance: not an expense until spent on the shop
        Reply manager = call("POST", "/finance/people", Map.of("name", "مدير " + run));
        assertEquals(201, manager.status());
        String person = "/finance/people/" + manager.data().path("id").asString();
        assertEquals(200, call("POST", person + "/withdraw", Map.of("accountId", hand, "currency", "SYP", "amount", 100000)).status());
        assertEquals(0, new BigDecimal("100000").compareTo(call("GET", person, null).data().path("owedSyp").decimalValue()));
        assertMoney("67.59", new tools.jackson.databind.node.DecimalNode(netProfit()));   // profit untouched
        assertEquals(200, call("POST", person + "/return", Map.of("accountId", shamCash, "currency", "SYP", "amount", 40000)).status());
        Reply tooMuch = call("POST", person + "/return", Map.of("accountId", hand, "currency", "SYP", "amount", 70000));
        assertEquals("ADVANCE_EXCEEDS_BALANCE", tooMuch.code());
        UUID packaging = UUID.fromString(call("GET", "/finance/expense-categories", null).data().valueStream()
                .filter(c -> c.path("name").asString().equals("تغليف")).findFirst().orElseThrow().path("id").asString());
        Reply spent = call("POST", person + "/spend", Map.of("currency", "SYP", "amount", 14500, "categoryId", packaging,
                "expenseDate", "2026-11-08", "description", "علب"));
        assertEquals(200, spent.status(), spent.body().toString());
        assertEquals(0, new BigDecimal("45500").compareTo(spent.data().path("owedSyp").decimalValue()));
        assertMoney("66.59", new tools.jackson.databind.node.DecimalNode(netProfit()));   // 1$ of packaging
        assertEquals(3, call("GET", person + "/entries", null).data().size());
        assertTrue(netWorth().subtract(startWorth).subtract(netProfit()).abs().compareTo(new BigDecimal("0.01")) < 0,
                "after advances: net worth changed by " + netWorth().subtract(startWorth) + ", profit " + netProfit());

        // --- inventory screen: lots in FIFO order with what is left, and the orders that took from them
        Reply productStock = call("GET", "/inventory/stock/" + a, null);
        assertEquals(200, productStock.status(), productStock.body().toString());
        JsonNode lots = productStock.data().path("lots");
        assertEquals(2, lots.size());
        assertEquals(0, lots.get(0).path("qtyLeft").asInt());                       // the 6$ lot is used up
        assertEquals(8, lots.get(1).path("qtyLeft").asInt());
        assertMoney("7.00", lots.get(1).path("unitCost"));
        assertTrue(lots.get(0).path("invoiceNumber").asString().startsWith("PI-"));
        assertEquals(3, productStock.data().path("recentOrders").size());
        assertTrue(productStock.data().path("summary").path("stocked").asBoolean());
        assertEquals(false, productStock.data().path("summary").path("low").asBoolean());
        assertEquals(404, call("GET", "/inventory/stock/" + UUID.randomUUID(), null).status());

        // --- the dashboard and the wealth page tell the same story as the books
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Damascus")).toString();
        Reply dash = call("GET", "/dashboard?from=" + today + "&to=" + today, null);
        assertEquals(200, dash.status(), dash.body().toString());
        JsonNode pnl = dash.data().path("pnl");
        assertMoney("144.00", pnl.path("revenueUsd"));
        assertMoney("74.00", pnl.path("cogsUsd"));
        assertMoney("66.59", pnl.path("netProfitUsd"));                            // = net profit of the books
        assertEquals(0, netProfit().setScale(2, RoundingMode.HALF_UP).compareTo(pnl.path("netProfitUsd").decimalValue()));
        // profit share: 25% by default, editable (name and percent)
        assertEquals(0, new BigDecimal("25").compareTo(dash.data().path("profitShare").path("percent").decimalValue()));
        assertMoney("16.65", dash.data().path("profitShare").path("amountUsd"));      // 25% of 66.59
        assertEquals("VALIDATION_ERROR", call("PUT", "/dashboard/profit-share", Map.of("label", "x", "percent", 150)).code());
        assertEquals(200, call("PUT", "/dashboard/profit-share", Map.of("label", "حصة الشريك", "percent", 10)).status());
        JsonNode share = call("GET", "/dashboard?from=" + today + "&to=" + today, null).data().path("profitShare");
        assertEquals("حصة الشريك", share.path("label").asString());
        assertMoney("6.66", share.path("amountUsd"));
        assertEquals(1, dash.data().path("returns").path("returned").asInt());
        assertMoney("1.03", dash.data().path("returns").path("deliveryCostUsd"));
        assertEquals("A", dash.data().path("topProducts").get(0).path("name").asString());
        assertEquals(0, dash.data().path("pending").path("count").asInt());
        BigDecimal dashWealth = dash.data().path("wealth").path("totalUsd").decimalValue();
        assertTrue(dashWealth.subtract(netWorth()).abs().compareTo(new BigDecimal("0.02")) < 0,
                "dashboard wealth " + dashWealth + " vs books " + netWorth());
        JsonNode wealth = call("GET", "/dashboard/wealth", null).data();
        assertEquals(0, dashWealth.compareTo(wealth.path("totals").path("totalUsd").decimalValue()));
        assertEquals(2, wealth.path("accounts").size());
        assertEquals(8, wealth.path("stock").get(0).path("available").asInt());
        assertMoney("56.00", wealth.path("totals").path("inventoryUsd"));           // 8 × 7$ (FIFO left the 7$ lot)
        assertEquals(1, wealth.path("advances").size());
        assertEquals("INVALID_PERIOD", call("GET", "/dashboard?from=" + today + "&to=2020-01-01", null).code());

        // the ledger lists every movement with what caused it
        JsonNode ledgerPage = call("GET", "/finance/movements?accountId=" + shamCash, null).data();
        assertTrue(ledgerPage.path("content").valueStream().anyMatch(m -> m.path("type").asString().equals("SALE_PAYMENT")
                && m.path("reference").path("label").asString().startsWith("SO-")));

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
