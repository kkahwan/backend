package com.si.batch.model;

import java.time.LocalDateTime;

public class PosSalesDetail {
    private final String receiptNo;
    private final String saleDate;
    private final String itemCode;
    private final String itemName;
    private final int unitPrice;
    private final int quantity;
    // 판매 시각 (null이면 DB 적재 시각)
    private final LocalDateTime saleTime;

    public PosSalesDetail(String receiptNo, String saleDate, String itemCode, String itemName, int unitPrice, int quantity) {
        this(receiptNo, saleDate, itemCode, itemName, unitPrice, quantity, null);
    }

    public PosSalesDetail(String receiptNo, String saleDate, String itemCode, String itemName, int unitPrice, int quantity, LocalDateTime saleTime) {
        this.receiptNo = receiptNo;
        this.saleDate = saleDate;
        this.itemCode = itemCode;
        this.itemName = itemName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.saleTime = saleTime;
    }

    public boolean isValid() {
        return receiptNo != null && !receiptNo.trim().isEmpty()
                && unitPrice > 0
                && quantity > 0;
    }

    public int calculateTotalPrice() {
        return this.unitPrice * this.quantity;
    }

    public String getReceiptNo() { return receiptNo; }
    public String getSaleDate() { return saleDate; }
    public String getItemCode() { return itemCode; }
    public String getItemName() { return itemName; }
    public int getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public LocalDateTime getSaleTime() { return saleTime; }
}
