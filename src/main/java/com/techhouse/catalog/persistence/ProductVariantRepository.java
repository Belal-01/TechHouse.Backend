package com.techhouse.catalog.persistence;

import com.techhouse.catalog.domain.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    @Query("select v from ProductVariant v where v.product.id in :productIds order by v.position")
    List<ProductVariant> findByProductIds(@Param("productIds") Collection<UUID> productIds);

    /** SKU already used by a variant of another (non-deleted) product in the store. */
    @Query("""
            select count(v) > 0 from ProductVariant v
            where v.storeId = :storeId and v.sku = :sku
              and v.product.id <> :excludeProductId and v.product.deletedAt is null
            """)
    boolean skuUsedByOtherProduct(@Param("storeId") UUID storeId,
                                  @Param("sku") String sku,
                                  @Param("excludeProductId") UUID excludeProductId);

    @Query("""
            select count(v) > 0 from ProductVariant v
            where v.storeId = :storeId and v.barcode = :barcode
              and v.product.id <> :excludeProductId and v.product.deletedAt is null
            """)
    boolean barcodeUsedByOtherProduct(@Param("storeId") UUID storeId,
                                      @Param("barcode") String barcode,
                                      @Param("excludeProductId") UUID excludeProductId);
}
