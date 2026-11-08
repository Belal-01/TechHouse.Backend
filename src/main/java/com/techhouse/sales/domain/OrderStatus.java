package com.techhouse.sales.domain;

/**
 * PENDING: left the shop, stock taken. Then exactly one final step:
 * DELIVERED (paid in full), RETURNED (not received; stock back, delivery fee stays an expense),
 * VOIDED (entered by mistake; everything reversed, including the delivery fee).
 */
public enum OrderStatus { PENDING, DELIVERED, RETURNED, VOIDED }
