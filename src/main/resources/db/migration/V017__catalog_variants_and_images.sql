-- Catalog: products now have variants (each with its own price/SKU/barcode), options, warranty and images.

-- categories use optimistic locking through BaseEntity
ALTER TABLE categories ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE products
    ADD COLUMN warranty_months INTEGER,
    ADD COLUMN option_names    JSONB NOT NULL DEFAULT '[]',
    ADD CONSTRAINT chk_product_warranty CHECK (warranty_months IS NULL OR warranty_months BETWEEN 0 AND 120);

-- Price and SKU now live on variants; the old product-level columns are kept for existing rows only.
ALTER TABLE products ALTER COLUMN selling_price DROP NOT NULL;

CREATE TABLE product_images (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id      UUID          NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    position        INTEGER       NOT NULL,
    content_type    VARCHAR(50)   NOT NULL,
    size_bytes      INTEGER       NOT NULL,
    data            BYTEA         NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_images_product ON product_images(product_id, position);

CREATE TABLE product_variants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id      UUID          NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    store_id        UUID          NOT NULL REFERENCES stores(id),
    position        INTEGER       NOT NULL DEFAULT 0,
    option_values   JSONB         NOT NULL DEFAULT '[]',
    sku             VARCHAR(50),
    barcode         VARCHAR(64),
    selling_price   NUMERIC(19,4) NOT NULL,
    image_id        UUID          REFERENCES product_images(id) ON DELETE SET NULL,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_variant_price CHECK (selling_price > 0)
);

CREATE INDEX idx_variants_product ON product_variants(product_id, position);
CREATE INDEX idx_variants_store_sku ON product_variants(store_id, sku) WHERE sku IS NOT NULL;
CREATE INDEX idx_variants_store_barcode ON product_variants(store_id, barcode) WHERE barcode IS NOT NULL;
CREATE UNIQUE INDEX uq_variant_product_sku ON product_variants(product_id, sku) WHERE sku IS NOT NULL;
CREATE UNIQUE INDEX uq_variant_product_barcode ON product_variants(product_id, barcode) WHERE barcode IS NOT NULL;

-- Carry existing single-price products over as one variant each
INSERT INTO product_variants (product_id, store_id, position, sku, selling_price)
SELECT id, store_id, 0, sku, selling_price
FROM products
WHERE selling_price IS NOT NULL AND selling_price > 0;
