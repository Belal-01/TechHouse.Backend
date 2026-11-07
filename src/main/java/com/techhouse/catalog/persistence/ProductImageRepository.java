package com.techhouse.catalog.persistence;

import com.techhouse.catalog.domain.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {

    List<ProductImageMeta> findByProductIdOrderByPositionAsc(UUID productId);

    List<ProductImageMeta> findByProductIdInOrderByPositionAsc(Collection<UUID> productIds);

    @Modifying
    @Query("update ProductImage i set i.position = :position where i.id = :id")
    int setPosition(@Param("id") UUID id, @Param("position") int position);

    @Modifying
    @Query("delete from ProductImage i where i.id = :id and i.productId = :productId")
    int deleteImage(@Param("id") UUID id, @Param("productId") UUID productId);
}
