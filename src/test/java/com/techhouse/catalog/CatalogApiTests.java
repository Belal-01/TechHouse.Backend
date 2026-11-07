package com.techhouse.catalog;

import com.techhouse.identity.domain.Store;
import com.techhouse.identity.domain.StoreMember;
import com.techhouse.identity.domain.User;
import com.techhouse.identity.persistence.StoreMemberRepository;
import com.techhouse.identity.persistence.StoreRepository;
import com.techhouse.identity.persistence.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end test of the catalog API, as the frontend uses it (through the real HTTP stack and database). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogApiTests {

    private static final UUID DEFAULT_STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** Smallest valid PNG: signature + a few bytes is enough, the API checks the magic number. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Value("${local.server.port}")
    int port;

    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired StoreMemberRepository storeMemberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String EMAIL = "catalog-test-" + RUN + "@example.com";

    private String token;

    @BeforeAll
    static void noop() {
    }

    @AfterAll
    static void done() {
    }

    private record Reply(int status, JsonNode body, byte[] raw, String contentType) {
        String code() {
            return body.path("code").asString();
        }
    }

    private Reply call(String method, String path, Object body, UUID store) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (store != null) b.header("X-Store-Id", store.toString());
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return send(b.build());
    }

    private Reply upload(String path, byte[] data, String filename) throws Exception {
        String boundary = "----test" + RUN;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("X-Store-Id", DEFAULT_STORE_ID.toString())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        return send(request);
    }

    private Reply send(HttpRequest request) throws Exception {
        HttpResponse<byte[]> res = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String type = res.headers().firstValue("Content-Type").orElse("");
        JsonNode body = type.contains("json") && res.body().length > 0
                ? json.readTree(new String(res.body(), StandardCharsets.UTF_8)) : json.createObjectNode();
        return new Reply(res.statusCode(), body, res.body(), type);
    }

    private Map<String, Object> variant(String sku, String price, List<String> values) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("optionValues", values);
        v.put("sku", sku);
        v.put("barcode", null);
        v.put("sellingPrice", new java.math.BigDecimal(price));
        v.put("imageId", null);
        v.put("active", true);
        return v;
    }

    private Map<String, Object> product(String name, UUID categoryId, List<String> options, List<Map<String, Object>> variants) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("description", "desc");
        p.put("categoryId", categoryId);
        p.put("warrantyMonths", 12);
        p.put("specifications", Map.of("RAM", "8GB"));
        p.put("active", true);
        p.put("optionNames", options);
        p.put("variants", variants);
        return p;
    }

    @Test
    void catalogFlow() throws Exception {
        // --- a user who belongs to the default store
        Store store = storeRepository.findById(DEFAULT_STORE_ID).orElseThrow();
        User user = new User();
        user.setEmail(EMAIL);
        user.setPasswordHash(passwordEncoder.encode("secret-pass"));
        user.setDisplayName("Catalog Test");
        user.setRole("OWNER");
        user = userRepository.save(user);
        StoreMember member = new StoreMember();
        member.setStore(store);
        member.setUser(user);
        member.setRole("OWNER");
        storeMemberRepository.save(member);

        Reply login = call("POST", "/api/v1/auth/login", Map.of("email", EMAIL, "password", "secret-pass"), null);
        assertEquals(200, login.status());
        token = login.body().path("data").path("accessToken").asString();

        // --- auth and store errors carry the codes the frontend maps
        String saved = token;
        token = null;
        assertEquals("UNAUTHORIZED", call("GET", "/api/v1/admin/catalog/categories", null, DEFAULT_STORE_ID).code());
        assertEquals(401, call("GET", "/api/v1/admin/catalog/categories", null, DEFAULT_STORE_ID).status());
        token = saved;
        assertEquals("STORE_HEADER_MISSING", call("GET", "/api/v1/admin/catalog/categories", null, null).code());
        Reply foreign = call("GET", "/api/v1/admin/catalog/categories", null, UUID.randomUUID());
        assertEquals(403, foreign.status());
        assertEquals("STORE_ACCESS_DENIED", foreign.code());

        // --- categories
        String categoryName = "Phones-" + RUN;
        Reply created = call("POST", "/api/v1/admin/catalog/categories", Map.of("name", categoryName), DEFAULT_STORE_ID);
        assertEquals(201, created.status());
        UUID categoryId = UUID.fromString(created.body().path("data").path("id").asString());
        Reply dupCategory = call("POST", "/api/v1/admin/catalog/categories", Map.of("name", categoryName.toUpperCase()), DEFAULT_STORE_ID);
        assertEquals(409, dupCategory.status());
        assertEquals("CATEGORY_NAME_EXISTS", dupCategory.code());
        assertTrue(dupCategory.body().path("fieldErrors").has("name"));
        Reply renamed = call("PUT", "/api/v1/admin/catalog/categories/" + categoryId, Map.of("name", categoryName + "-x"), DEFAULT_STORE_ID);
        assertEquals(200, renamed.status());
        assertTrue(call("GET", "/api/v1/admin/catalog/categories", null, DEFAULT_STORE_ID).body().path("data").size() >= 1);

        // --- validation
        Reply invalid = call("POST", "/api/v1/admin/catalog/products",
                product("", categoryId, List.of(), List.of(variant("A", "10", List.of()))), DEFAULT_STORE_ID);
        assertEquals(400, invalid.status());
        assertEquals("VALIDATION_ERROR", invalid.code());
        assertTrue(invalid.body().path("fieldErrors").has("name"));
        Reply noOptions = call("POST", "/api/v1/admin/catalog/products",
                product("P", categoryId, List.of(), List.of(variant("A", "10", List.of()), variant("B", "10", List.of()))), DEFAULT_STORE_ID);
        assertEquals("PRODUCT_OPTIONS_REQUIRED", noOptions.code());
        Reply mismatch = call("POST", "/api/v1/admin/catalog/products",
                product("P", categoryId, List.of("Color"), List.of(variant("A", "10", List.of()))), DEFAULT_STORE_ID);
        assertEquals("VARIANT_OPTIONS_MISMATCH", mismatch.code());
        assertTrue(mismatch.body().path("fieldErrors").has("variants[0].optionValues"));

        // --- create a product with two variants
        String sku1 = "SKU-" + RUN + "-1";
        String sku2 = "SKU-" + RUN + "-2";
        Reply p = call("POST", "/api/v1/admin/catalog/products", product("Phone " + RUN, categoryId, List.of("Color"),
                List.of(variant(sku1, "100.50", List.of("Black")), variant(sku2, "120", List.of("White")))), DEFAULT_STORE_ID);
        assertEquals(201, p.status(), p.body().toString());
        JsonNode data = p.body().path("data");
        UUID productId = UUID.fromString(data.path("id").asString());
        assertEquals(2, data.path("variants").size());
        assertEquals("Black", data.path("variants").get(0).path("optionValues").get(0).asString());
        assertEquals("8GB", data.path("specifications").path("RAM").asString());
        assertEquals(categoryName + "-x", data.path("category").path("name").asString());
        int version = data.path("version").asInt();

        // SKU used by another product in the store
        Reply dupSku = call("POST", "/api/v1/admin/catalog/products",
                product("Other " + RUN, null, List.of(), List.of(variant(sku1, "5", List.of()))), DEFAULT_STORE_ID);
        assertEquals(409, dupSku.status());
        assertEquals("VARIANT_SKU_EXISTS", dupSku.code());
        assertTrue(dupSku.body().path("fieldErrors").has("variants[0].sku"));

        // --- list, search, category filter
        Reply list = call("GET", "/api/v1/admin/catalog/products?page=0&size=10&sort=name&search=" + sku2, null, DEFAULT_STORE_ID);
        assertEquals(200, list.status());
        JsonNode page = list.body().path("data");
        assertEquals(1, page.path("totalElements").asInt());
        JsonNode summary = page.path("content").get(0);
        assertEquals(2, summary.path("variantCount").asInt());
        assertEquals(100.5, summary.path("minPrice").asDouble());
        assertEquals(120.0, summary.path("maxPrice").asDouble());
        assertEquals(1, call("GET", "/api/v1/admin/catalog/products?categoryId=" + categoryId, null, DEFAULT_STORE_ID)
                .body().path("data").path("totalElements").asInt());

        // --- images
        Reply bad = upload("/api/v1/admin/catalog/products/" + productId + "/images", "not an image".getBytes(), "x.png");
        assertEquals(400, bad.status());
        assertEquals("IMAGE_TYPE_NOT_ALLOWED", bad.code());
        Reply img1 = upload("/api/v1/admin/catalog/products/" + productId + "/images", PNG, "a.png");
        Reply img2 = upload("/api/v1/admin/catalog/products/" + productId + "/images", PNG, "b.png");
        assertEquals(201, img1.status(), img1.body().toString());
        String id1 = img1.body().path("data").path("id").asString();
        String id2 = img2.body().path("data").path("id").asString();
        Reply file = call("GET", "/api/v1/admin/catalog/images/" + id1, null, DEFAULT_STORE_ID);
        assertEquals(200, file.status());
        assertEquals("image/png", file.contentType());
        assertEquals(PNG.length, file.raw().length);
        Reply order = call("PUT", "/api/v1/admin/catalog/products/" + productId + "/images/order",
                Map.of("imageIds", List.of(id2, id1)), DEFAULT_STORE_ID);
        assertEquals(200, order.status());
        assertEquals(id2, order.body().path("data").get(0).path("id").asString());
        assertEquals("IMAGE_ORDER_INVALID", call("PUT", "/api/v1/admin/catalog/products/" + productId + "/images/order",
                Map.of("imageIds", List.of(id2)), DEFAULT_STORE_ID).code());

        // --- update: keep variant 1 (with image), drop variant 2, add a new one; optimistic locking
        JsonNode current = call("GET", "/api/v1/admin/catalog/products/" + productId, null, DEFAULT_STORE_ID).body().path("data");
        assertEquals(id2, current.path("images").get(0).path("id").asString());
        Map<String, Object> keep = variant(sku1, "99", List.of("Black"));
        keep.put("id", current.path("variants").get(0).path("id").asString());
        keep.put("imageId", id1);
        Map<String, Object> update = product("Phone " + RUN + " v2", categoryId, List.of("Color"),
                new ArrayList<>(List.of(keep, variant(sku2 + "n", "130", List.of("Red")))));
        update.put("version", current.path("version").asInt());
        Reply updated = call("PUT", "/api/v1/admin/catalog/products/" + productId, update, DEFAULT_STORE_ID);
        assertEquals(200, updated.status(), updated.body().toString());
        assertEquals("Phone " + RUN + " v2", updated.body().path("data").path("name").asString());
        assertEquals(2, updated.body().path("data").path("variants").size());
        assertEquals(id1, updated.body().path("data").path("variants").get(0).path("imageId").asString());
        assertTrue(updated.body().path("data").path("version").asInt() > version);

        Reply stale = call("PUT", "/api/v1/admin/catalog/products/" + productId, update, DEFAULT_STORE_ID);
        assertEquals(409, stale.status());
        assertEquals("CONCURRENT_MODIFICATION", stale.code());

        // deleting an image clears the variant that used it
        assertEquals(204, call("DELETE", "/api/v1/admin/catalog/products/" + productId + "/images/" + id1, null, DEFAULT_STORE_ID).status());
        JsonNode afterDelete = call("GET", "/api/v1/admin/catalog/products/" + productId, null, DEFAULT_STORE_ID).body().path("data");
        assertEquals(1, afterDelete.path("images").size());
        assertEquals(0, afterDelete.path("images").get(0).path("position").asInt());
        assertTrue(afterDelete.path("variants").get(0).path("imageId").isMissingNode()
                || afterDelete.path("variants").get(0).path("imageId").isNull());

        // --- category in use, then delete everything
        Reply inUse = call("DELETE", "/api/v1/admin/catalog/categories/" + categoryId, null, DEFAULT_STORE_ID);
        assertEquals(409, inUse.status());
        assertEquals("CATEGORY_IN_USE", inUse.code());
        assertEquals(204, call("DELETE", "/api/v1/admin/catalog/products/" + productId, null, DEFAULT_STORE_ID).status());
        Reply gone = call("GET", "/api/v1/admin/catalog/products/" + productId, null, DEFAULT_STORE_ID);
        assertEquals(404, gone.status());
        assertEquals("PRODUCT_NOT_FOUND", gone.code());
        assertEquals(204, call("DELETE", "/api/v1/admin/catalog/categories/" + categoryId, null, DEFAULT_STORE_ID).status());

        // the SKU of a deleted product can be reused
        Reply reuse = call("POST", "/api/v1/admin/catalog/products",
                product("Again " + RUN, null, List.of(), List.of(variant(sku1, "5", List.of()))), DEFAULT_STORE_ID);
        assertEquals(201, reuse.status(), reuse.body().toString());

        // --- cleanup (test data only)
        jdbc.update("delete from product_variants where store_id = ? and sku like ?", DEFAULT_STORE_ID, "SKU-" + RUN + "%");
        jdbc.update("delete from products where store_id = ? and name like ?", DEFAULT_STORE_ID, "%" + RUN + "%");
        jdbc.update("delete from store_members where user_id = ?", user.getId());
        jdbc.update("delete from users where id = ?", user.getId());
    }
}
