package com.techhouse.identity.web.dto;

import java.util.UUID;

public record StoreDto(
        UUID id,
        String name,
        String countryCode,
        String localCurrency
) {}
