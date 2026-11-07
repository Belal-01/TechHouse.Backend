package com.techhouse.catalog.web.dto;

import java.util.UUID;

public record ImageResponse(UUID id, int position, String contentType, int sizeBytes) {}
