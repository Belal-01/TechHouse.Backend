package com.techhouse.sales.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** @param accountId the box the customer's money went into (chosen each time) */
public record DeliverRequest(@NotNull UUID accountId, @Size(max = 1000) String note) {}
