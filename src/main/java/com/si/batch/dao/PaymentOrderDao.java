package com.si.batch.dao;

import com.si.batch.model.PaymentOrder;
import com.si.batch.service.OrderService.Line;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// 결제 주문 + 주문 상품 스냅샷 (테이블 정의: sql/shop_schema.sql)
public class PaymentOrderDao {

    public void insert(Connection conn, String orderId, long memberId, String orderName, long amount, List<Line> lines) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO PAYMENT_ORDER (ORDER_ID, MEMBER_ID, ORDER_NAME, AMOUNT) VALUES (?, ?, ?, ?)")) {
            pstmt.setString(1, orderId);
            pstmt.setLong(2, memberId);
            pstmt.setString(3, orderName);
            pstmt.setLong(4, amount);
            pstmt.executeUpdate();
        }
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO PAYMENT_ORDER_ITEM (ORDER_ID, LINE_NO, ITEM_CODE, ITEM_NAME, UNIT_PRICE, QUANTITY) VALUES (?, ?, ?, ?, ?, ?)")) {
            int lineNo = 1;
            for (Line l : lines) {
                pstmt.setString(1, orderId);
                pstmt.setInt(2, lineNo++);
                pstmt.setString(3, l.itemCode());
                pstmt.setString(4, l.itemName());
                pstmt.setInt(5, l.unitPrice());
                pstmt.setInt(6, l.quantity());
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    public PaymentOrder select(Connection conn, String orderId) throws SQLException {
        String sql = "SELECT ORDER_ID, MEMBER_ID, ORDER_NAME, AMOUNT, STATUS, PAYMENT_KEY, METHOD, APPROVED_AT "
                + "FROM PAYMENT_ORDER WHERE ORDER_ID = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, orderId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next()
                        ? new PaymentOrder(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                                rs.getString(6), rs.getString(7), rs.getObject(8, LocalDateTime.class))
                        : null;
            }
        }
    }

    public List<Line> selectItems(Connection conn, String orderId) throws SQLException {
        String sql = "SELECT ITEM_CODE, ITEM_NAME, UNIT_PRICE, QUANTITY FROM PAYMENT_ORDER_ITEM WHERE ORDER_ID = ? ORDER BY LINE_NO";
        List<Line> list = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, orderId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new Line(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4)));
                }
            }
        }
        return list;
    }

    /** from 상태일 때만 to로 바꿈 (조건부 UPDATE라 동시에 두 번 승인 요청이 와도 한 쪽만 true) */
    public boolean changeStatus(Connection conn, String orderId, String from, String to) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE PAYMENT_ORDER SET STATUS = ? WHERE ORDER_ID = ? AND STATUS = ?")) {
            pstmt.setString(1, to);
            pstmt.setString(2, orderId);
            pstmt.setString(3, from);
            return pstmt.executeUpdate() == 1;
        }
    }

    public void markDone(Connection conn, String orderId, String paymentKey, String method, LocalDateTime approvedAt) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE PAYMENT_ORDER SET STATUS = 'DONE', PAYMENT_KEY = ?, METHOD = ?, APPROVED_AT = ? WHERE ORDER_ID = ?")) {
            pstmt.setString(1, paymentKey);
            pstmt.setString(2, method);
            pstmt.setObject(3, approvedAt);
            pstmt.setString(4, orderId);
            pstmt.executeUpdate();
        }
    }

    public void markFailed(Connection conn, String orderId, String paymentKey, String reason) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE PAYMENT_ORDER SET STATUS = 'FAILED', PAYMENT_KEY = ?, FAIL_REASON = ? WHERE ORDER_ID = ?")) {
            pstmt.setString(1, paymentKey);
            pstmt.setString(2, reason == null || reason.length() <= 200 ? reason : reason.substring(0, 200));
            pstmt.setString(3, orderId);
            pstmt.executeUpdate();
        }
    }
}
