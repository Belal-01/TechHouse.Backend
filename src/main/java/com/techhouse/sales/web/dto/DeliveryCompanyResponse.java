package com.techhouse.sales.web.dto;

import java.time.Instant;
import java.util.UUID;

public record DeliveryCompanyResponse(UUID id, String name, String contactInfo, boolean active, int version,
                                      Instant createdAt, Instant updatedAt) {}
