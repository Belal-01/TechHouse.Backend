package com.techhouse.procurement.web.dto;

import jakarta.validation.constraints.Size;

public record VoidInvoiceRequest(@Size(max = 1000) String note) {}
