package com.si.batch.model;

import java.time.LocalDateTime;

// PRODUCT 테이블 1행 (예전 ProductCatalog enum 대체)
public record Product(String itemCode, String itemName, int unitPrice, String description, LocalDateTime createdAt) {
}
