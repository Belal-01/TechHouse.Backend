package com.techhouse.catalog.persistence;

import java.util.UUID;

/** Closed projection: selects image metadata without the {@code data} column. */
public interface ProductImageMeta {

    UUID getId();

    UUID getProductId();

    int getPosition();

    String getContentType();

    int getSizeBytes();
}
