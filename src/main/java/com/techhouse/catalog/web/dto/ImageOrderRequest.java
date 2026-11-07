package com.techhouse.catalog.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ImageOrderRequest(@NotNull List<UUID> imageIds) {}
