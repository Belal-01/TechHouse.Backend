package com.techhouse.finance.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** A box with its two balances (may be negative if the books are behind reality). */
public record CashAccountResponse(UUID id, String name, boolean isDefault, boolean active, BigDecimal usd,
                                  BigDecimal syp, int version) {}
