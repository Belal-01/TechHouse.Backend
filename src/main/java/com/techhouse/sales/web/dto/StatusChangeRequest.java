package com.techhouse.sales.web.dto;

import jakarta.validation.constraints.Size;

public record StatusChangeRequest(@Size(max = 1000) String note) {}
