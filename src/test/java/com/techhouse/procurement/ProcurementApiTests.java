package com.techhouse.procurement;

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

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end test of suppliers and purchase invoices (landed cost, FIFO lots, ledger, PDF attachment). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProcurementApiTests {

    private static final UUID STORE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String EMAIL = "procurement-test-" + RUN + "@example.com";
    private static final byte[] PDF = "%PDF-1.4\n%test\n".getBytes(StandardCharsets.US_ASCII);

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

    private record Reply(int status, JsonNode body, byte[] raw, String contentType) {
        String code() {
            return body.path("code").asString();
        }

        JsonNode data() {
            return body.path("data");
        }
    }

    private Reply call(String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("X-Store-Id", STORE.toString());
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return send(b.build());
    }

    /** Same as {@link #call} but under /api/v1/admin/sales. */
    private Reply callSales(String method, String path, Object body) throws Exception {
        return call(method, "/api/v1/admin/sales" + path, body);
    }

    private Reply upload(String path, byte[] data, String filename) throws Exception {
        String boundary = "----test" + RUN;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("X-Store-Id", STORE.toString())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build());
    }

    private Reply send(HttpRequest request) throws Exception {
        HttpResponse<byte[]> res = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String type = res.headers().firstValue("Content-Type").orElse("");
        JsonNode body = type.contains("json") && res.body().length > 0
                ? json.readTree(new String(res.body(), StandardCharsets.UTF_8)) : json.createObjectNode();
        return new Reply(res.statusCode(), body, res.body(), type);
    }

    private UUID newProduct(String name) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name + "-" + RUN);
        p.put("sellingPrice", new BigDecimal("50"));
        p.put("active", true);
        Reply r = call("POST", "/api/v1/admin/catalog/products", p);
        assertEquals(201, r.status());
        return UUID.fromString(r.data().path("id").asString());
    }

    private static Map<String, Object> line(UUID product, int qty, String price) {
        return Map.of("productId", product, "quantity", qty, "unitPriceUsd", new BigDecimal(price));
    }

    private static Map<String, Object> invoice(UUID supplier, String shipping, String paidFrom, List<?> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supplierId", supplier);
        m.put("invoiceDate", "2026-11-01");
        m.put("shippingCostUsd", new BigDecimal(shipping));
        m.put("paidFrom", paidFrom);
        m.put("items", items);
        return m;
    }

    private BigDecimal ledgerSum(UUID invoiceId) {
        return jdbc.queryForObject("select coalesce(sum(amount), 0) from cash_movements where reference_id = ?",
                BigDecimal.class, invoiceId);
    }

    @Test
    void procurementFlow() throws Exception {
        Store store = storeRepository.findById(STORE).orElseThrow();
        User user = new User();
        user.setEmail(EMAIL);
        user.setPasswordHash(passwordEncoder.encode("secret-pass"));
        user.setDisplayName("Procurement Test");
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

        // --- suppliers
        String base = "/api/v1/admin/procurement/suppliers";
        Reply noName = call("POST", base, Map.of("name", " "));
        assertEquals("VALIDATION_ERROR", noName.code());
        Reply s1 = call("POST", base, Map.of("name", "Shenzhen Co " + RUN, "contactInfo", "+86 123"));
        assertEquals(201, s1.status());
        UUID supplierId = UUID.fromString(s1.data().path("id").asString());
        Reply dup = call("POST", base, Map.of("name", ("shenzhen co " + RUN).toUpperCase()));
        assertEquals(409, dup.status());
        assertEquals("SUPPLIER_NAME_EXISTS", dup.code());
        assertTrue(dup.body().path("fieldErrors").has("name"));
        assertEquals(1, call("GET", base + "?q=shenzhen%20co%20" + RUN, null).data().size());
        Reply stale = call("PUT", base + "/" + supplierId, Map.of("name", "Renamed " + RUN, "version", 99));
        assertEquals("CONCURRENT_MODIFICATION", stale.code());
        Reply renamed = call("PUT", base + "/" + supplierId, Map.of("name", "Shenzhen Co " + RUN, "notes", "good",
                "version", s1.data().path("version").asInt()));
        assertEquals(200, renamed.status());
        assertEquals("good", renamed.data().path("notes").asString());

        // --- invoice: price 10 x100 and 20 x50, shipping 200  => shares 100/100, landed 11 and 22
        UUID a = newProduct("A");
        UUID b = newProduct("B");
        String inv = "/api/v1/admin/procurement/invoices";
        Reply created = call("POST", inv, invoice(supplierId, "200", "INCOME", List.of(line(a, 100, "10"), line(b, 50, "20"))));
        assertEquals(201, created.status(), created.body().toString());
        JsonNode d = created.data();
        assertTrue(d.path("invoiceNumber").asString().startsWith("PI-"));
        assertEquals(0, new BigDecimal("2000").compareTo(d.path("itemsTotalUsd").decimalValue()));
        assertEquals(0, new BigDecimal("2200").compareTo(d.path("totalUsd").decimalValue()));
        assertEquals(0, new BigDecimal("11").compareTo(d.path("items").get(0).path("landedUnitCost").decimalValue()));
        assertEquals(0, new BigDecimal("22").compareTo(d.path("items").get(1).path("landedUnitCost").decimalValue()));
        assertEquals(0, new BigDecimal("100").compareTo(d.path("items").get(0).path("extraCostUsd").decimalValue()));
        UUID invoiceId = UUID.fromString(d.path("id").asString());

        // one FIFO lot per line, costed at the landed cost
        List<Map<String, Object>> lots = jdbc.queryForList("""
                select l.qty_in, l.qty_left, l.unit_cost from inventory_lots l
                join invoice_items i on i.id = l.invoice_item_id where i.invoice_id = ? order by l.unit_cost""", invoiceId);
        assertEquals(2, lots.size());
        assertEquals(100, lots.get(0).get("qty_left"));
        assertEquals(0, new BigDecimal("11").compareTo((BigDecimal) lots.get(0).get("unit_cost")));

        // paid from income: cash goes down by the whole invoice, once
        assertEquals(0, new BigDecimal("-2200").compareTo(ledgerSum(invoiceId)));
        assertEquals(1, jdbc.queryForObject("select count(*) from cash_movements where reference_id = ?", Integer.class, invoiceId));

        // inventory value + cash change must match: landed value of lots == what was paid
        BigDecimal stockValue = jdbc.queryForObject("""
                select sum(l.qty_in * l.unit_cost) from inventory_lots l
                join invoice_items i on i.id = l.invoice_item_id where i.invoice_id = ?""", BigDecimal.class, invoiceId);
        assertEquals(0, new BigDecimal("2200").compareTo(stockValue));

        // --- rounding: 3 equal lines, shipping 100 => 33.3333 + 33.3333 + 33.3334
        UUID c = newProduct("C");
        Reply rounded = call("POST", inv, invoice(null, "100", "EXTERNAL_CAPITAL",
                List.of(line(a, 1, "10"), line(b, 1, "10"), line(c, 1, "10"))));
        assertEquals(201, rounded.status());
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode item : rounded.data().path("items")) sum = sum.add(item.path("extraCostUsd").decimalValue());
        assertEquals(0, new BigDecimal("100").compareTo(sum));
        assertEquals(0, new BigDecimal("33.3334").compareTo(rounded.data().path("items").get(2).path("extraCostUsd").decimalValue()));
        // external capital: money in and out again, net zero on cash, but two ledger rows
        UUID roundedId = UUID.fromString(rounded.data().path("id").asString());
        assertEquals(0, BigDecimal.ZERO.compareTo(ledgerSum(roundedId)));
        assertEquals(2, jdbc.queryForObject("select count(*) from cash_movements where reference_id = ?", Integer.class, roundedId));

        // --- no shipping at all
        Reply plain = call("POST", inv, invoice(null, "0", "INCOME", List.of(line(a, 2, "5"))));
        assertEquals(0, new BigDecimal("5").compareTo(plain.data().path("items").get(0).path("landedUnitCost").decimalValue()));

        // --- validation and lookups
        assertEquals("VALIDATION_ERROR", call("POST", inv, invoice(null, "0", "INCOME", List.of())).code());
        assertEquals("VALIDATION_ERROR", call("POST", inv, invoice(null, "-1", "INCOME", List.of(line(a, 1, "1")))).code());
        assertEquals("VALIDATION_ERROR", call("POST", inv, invoice(null, "0", "CASH", List.of(line(a, 1, "1")))).code());
        assertEquals("VALIDATION_ERROR", call("POST", inv, invoice(null, "0", "INCOME", List.of(line(a, 0, "1")))).code());
        Reply missingProduct = call("POST", inv, invoice(null, "0", "INCOME", List.of(line(a, 1, "1"), line(UUID.randomUUID(), 1, "1"))));
        assertEquals(404, missingProduct.status());
        assertEquals("PRODUCT_NOT_FOUND", missingProduct.code());
        assertTrue(missingProduct.body().path("fieldErrors").has("items[1].productId"));
        assertEquals("SUPPLIER_NOT_FOUND", call("POST", inv, invoice(UUID.randomUUID(), "0", "INCOME", List.of(line(a, 1, "1")))).code());
        // a rejected invoice leaves no trace
        assertEquals(3, jdbc.queryForObject("""
                select count(*) from purchase_invoices where store_id = ? and invoice_number like 'PI-%'
                and id in (select invoice_id from invoice_items where product_id in (?, ?, ?))""", Integer.class, STORE, a, b, c));

        // --- PDF attachment
        String attach = inv + "/" + invoiceId + "/attachment";
        assertEquals(404, call("GET", attach, null).status());
        assertEquals("FILE_NOT_PDF", upload(attach, "not a pdf".getBytes(StandardCharsets.UTF_8), "x.pdf").code());
        assertEquals(204, upload(attach, PDF, "supplier-invoice.pdf").status());
        assertEquals(204, upload(attach, PDF, "again.pdf").status());
        Reply file = call("GET", attach, null);
        assertEquals(200, file.status());
        assertTrue(file.contentType().contains("application/pdf"));
        assertEquals(PDF.length, file.raw().length);
        assertTrue(call("GET", inv + "/" + invoiceId, null).data().path("hasAttachment").asBoolean());
        assertEquals(204, call("DELETE", attach, null).status());
        assertEquals(404, call("GET", attach, null).status());
        assertEquals(404, call("GET", inv + "/" + UUID.randomUUID() + "/attachment", null).status());

        // --- listing
        Reply list = call("GET", inv + "?supplierId=" + supplierId, null);
        assertEquals(1, list.data().path("totalElements").asInt());
        JsonNode row = list.data().path("content").get(0);
        assertEquals(2, row.path("itemCount").asInt());
        assertEquals(0, new BigDecimal("2200").compareTo(row.path("totalUsd").decimalValue()));
        assertEquals("Shenzhen Co " + RUN, row.path("supplier").path("name").asString());
        assertTrue(call("GET", inv, null).data().path("totalElements").asInt() >= 3);

        // --- voiding an invoice entered by mistake: stock out, payment reversed, kept for the record
        Reply mistake = call("POST", inv, invoice(null, "20", "INCOME", List.of(line(c, 5, "10"))));
        UUID mistakeId = UUID.fromString(mistake.data().path("id").asString());
        assertTrue(mistake.data().path("voidable").asBoolean());
        assertEquals(0, new BigDecimal("-70").compareTo(ledgerSum(mistakeId)));
        Reply voided = call("POST", inv + "/" + mistakeId + "/void", Map.of("note", "أُدخلت مرتين"));
        assertEquals(200, voided.status(), voided.body().toString());
        assertTrue(voided.data().path("voidedAt").isString());
        assertEquals("أُدخلت مرتين", voided.data().path("voidNote").asString());
        assertEquals(false, voided.data().path("voidable").asBoolean());
        assertEquals(0, BigDecimal.ZERO.compareTo(ledgerSum(mistakeId)));          // the 70$ is back in the box
        assertEquals(0, jdbc.queryForObject("""
                select coalesce(sum(l.qty_left), 0) from inventory_lots l join invoice_items i on i.id = l.invoice_item_id
                where i.invoice_id = ?""", Integer.class, mistakeId));
        assertEquals("INVOICE_ALREADY_VOIDED", call("POST", inv + "/" + mistakeId + "/void", null).code());
        assertTrue(call("GET", inv, null).data().path("content").valueStream()
                .anyMatch(r -> r.path("id").asString().equals(mistakeId.toString()) && r.path("voided").asBoolean()));

        // an invoice whose units were taken by an order can't be voided... until that order is undone
        Reply order = callSales("POST", "/orders", Map.of("customerName", "test", "currency", "USD",
                "items", List.of(Map.of("productId", a, "quantity", 1, "unitPrice", 50))));
        assertEquals(201, order.status(), order.body().toString());
        assertEquals(false, call("GET", inv + "/" + invoiceId, null).data().path("voidable").asBoolean());
        Reply refused = call("POST", inv + "/" + invoiceId + "/void", null);
        assertEquals(409, refused.status());
        assertEquals("INVOICE_STOCK_USED", refused.code());
        assertEquals(200, callSales("POST", "/orders/" + order.data().path("id").asString() + "/void", null).status());
        assertTrue(call("GET", inv + "/" + invoiceId, null).data().path("voidable").asBoolean());

        // --- a supplier with invoices can be deactivated but not deleted
        assertEquals("SUPPLIER_IN_USE", call("DELETE", base + "/" + supplierId, null).code());
        int version = call("GET", base + "/" + supplierId, null).data().path("version").asInt();
        Reply off = call("PUT", base + "/" + supplierId, Map.of("name", "Shenzhen Co " + RUN, "active", false, "version", version));
        assertEquals(200, off.status());
        assertEquals("SUPPLIER_INACTIVE", call("POST", inv, invoice(supplierId, "0", "INCOME", List.of(line(a, 1, "1")))).code());
        Reply lonely = call("POST", base, Map.of("name", "Lonely " + RUN));
        assertEquals(204, call("DELETE", base + "/" + lonely.data().path("id").asString(), null).status());

        // --- cleanup (the ledger is append-only, so its test rows stay)
        jdbc.update("delete from order_status_history where order_id in (select order_id from order_items where product_id in (?, ?, ?))", a, b, c);
        jdbc.update("delete from order_item_allocations where order_item_id in (select id from order_items where product_id in (?, ?, ?))", a, b, c);
        jdbc.update("delete from orders where id in (select order_id from order_items where product_id in (?, ?, ?))", a, b, c);
        jdbc.update("delete from inventory_lots where product_id in (?, ?, ?)", a, b, c);
        jdbc.update("delete from purchase_invoices where id in (select invoice_id from invoice_items where product_id in (?, ?, ?))", a, b, c);
        jdbc.update("delete from invoice_items where product_id in (?, ?, ?)", a, b, c);
        jdbc.update("delete from suppliers where store_id = ? and name like ?", STORE, "%" + RUN + "%");
        jdbc.update("delete from products where store_id = ? and name like ?", STORE, "%" + RUN + "%");
        jdbc.update("delete from store_members where user_id = ?", user.getId());
        jdbc.update("delete from users where id = ?", user.getId());
    }
}
