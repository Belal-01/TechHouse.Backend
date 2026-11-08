package com.techhouse.finance.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CashAccountRequest(@NotBlank @Size(max = 100) String name, Boolean active, Integer version) {}
