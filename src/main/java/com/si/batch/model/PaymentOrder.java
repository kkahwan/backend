package com.si.batch.model;

import java.time.LocalDateTime;

// PAYMENT_ORDER 테이블 1행. status: READY -> PAYING -> DONE / FAILED
public record PaymentOrder(String orderId, long memberId, String orderName, long amount, String status,
                           String paymentKey, String method, LocalDateTime approvedAt) {
}
