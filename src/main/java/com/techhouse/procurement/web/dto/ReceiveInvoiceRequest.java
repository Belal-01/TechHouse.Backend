package com.techhouse.procurement.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/** The goods arrived: the costs paid on arrival (sea freight, customs...), then the stock becomes sellable. */
public record ReceiveInvoiceRequest(@Size(max = 50) List<@Valid InvoiceCostRequest> costs) {}
