package com.paymesh.dto;
import java.math.BigDecimal;
public record Experiment6PaymentRequest(String paymentId, BigDecimal amount, String method) {}
