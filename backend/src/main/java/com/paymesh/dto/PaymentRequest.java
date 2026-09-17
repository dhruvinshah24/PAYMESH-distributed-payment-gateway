package com.paymesh.dto;
import java.math.BigDecimal;
public record PaymentRequest(String orderId, String customerId, BigDecimal amount, String currency, String method, String processor, String idempotencyKey) {}
