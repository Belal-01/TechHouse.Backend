-- Variants are removed: every sellable item is its own product, with one price, SKU and barcode.
-- Existing variants are turned into products first, so no data is lost.

ALTER TABLE products DROP CONSTRAINT IF EXISTS uq_product_sku;
ALTER TABLE products ADD COLUMN barcode VARCHAR(64);

CREATE TEMP TABLE _ranked AS
SELECT v.*,
       row_number() OVER (PARTITION BY v.product_id ORDER BY v.position, v.created_at, v.id) AS rn,
       count(*)     OVER (PARTITION BY v.product_id) AS cnt
FROM product_variants v;

-- Variants after the first become new products named "<product> - <option values>".
INSERT INTO products (store_id, category_id, sku, barcode, name, description, selling_price,
                      specifications, active, deleted_at, warranty_months)
SELECT p.store_id, p.category_id, r.sku, r.barcode,
       left(p.name || CASE WHEN jsonb_array_length(r.option_values) > 0
                           THEN ' - ' || (SELECT string_agg(e.value, ' / ' ORDER BY e.ord)
                                          FROM jsonb_array_elements_text(r.option_values) WITH ORDINALITY AS e(value, ord))
                           ELSE '' END, 255),
       p.description, r.selling_price, p.specifications, p.active AND r.active, p.deleted_at, p.warranty_months
FROM _ranked r
JOIN products p ON p.id = r.product_id
WHERE r.rn > 1;

-- The first variant moves onto its product (renamed too when the product had several variants).
UPDATE products p
SET sku = r.sku,
    barcode = r.barcode,
    selling_price = r.selling_price,
    active = p.active AND r.active,
    name = left(p.name || CASE WHEN r.cnt > 1 AND jsonb_array_length(r.option_values) > 0
                               THEN ' - ' || (SELECT string_agg(e.value, ' / ' ORDER BY e.ord)
                                              FROM jsonb_array_elements_text(r.option_values) WITH ORDINALITY AS e(value, ord))
                               ELSE '' END, 255)
FROM _ranked r
WHERE r.product_id = p.id AND r.rn = 1;

UPDATE products SET selling_price = 0 WHERE selling_price IS NULL;
ALTER TABLE products ALTER COLUMN selling_price SET NOT NULL;

DROP TABLE _ranked;

ALTER TABLE products DROP COLUMN option_names;
DROP TABLE product_variants;

-- SKU and barcode are unique per store among non-deleted products.
CREATE UNIQUE INDEX uq_product_sku_active ON products(store_id, sku)
    WHERE sku IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX uq_product_barcode_active ON products(store_id, barcode)
    WHERE barcode IS NOT NULL AND deleted_at IS NULL;
