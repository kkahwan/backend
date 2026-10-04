package com.si.batch.dao;

import com.si.batch.model.PosSalesDetail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PosSalesDao {

    // SALE_TIME은 DATETIME(4)(소수점 4자리)라 그 아래 자리가 반올림되면 23:59:59.99999가 다음날 00:00:00이 됨 -> 버림
    public static LocalDateTime toDbTime(LocalDateTime t) {
        return t.withNano(t.getNano() / 100_000 * 100_000);
    }

    public int insertBatchDetails(Connection conn, List list) throws SQLException {
        String insertSql = "INSERT IGNORE INTO POS_SALES_DETAIL "
                + "(RECEIPT_NO, SALE_DATE, ITEM_CODE, ITEM_NAME, UNIT_PRICE, QUANTITY, TOTAL_PRICE, SALE_TIME) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, COALESCE(?, NOW(4)))";

        try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
            for (Object obj : list) {
                PosSalesDetail detail = (PosSalesDetail) obj;
                pstmt.setString(1, detail.getReceiptNo());
                pstmt.setString(2, detail.getSaleDate());
                pstmt.setString(3, detail.getItemCode());
                pstmt.setString(4, detail.getItemName());
                pstmt.setInt(5, detail.getUnitPrice());
                pstmt.setInt(6, detail.getQuantity());
                pstmt.setInt(7, detail.calculateTotalPrice());
                pstmt.setObject(8, detail.getSaleTime() == null ? null : toDbTime(detail.getSaleTime()));
                pstmt.addBatch();
            }

            // INSERT IGNORE로 무시된 중복 행은 0이 반환되므로 실제 적재된 건수만 카운트
            int inserted = 0;
            for (int r : pstmt.executeBatch()) {
                if (r > 0 || r == Statement.SUCCESS_NO_INFO) inserted++;
            }
            return inserted;
        }
    }

    public void upsertDailySummary(Connection conn, String batchDate) throws SQLException {
        String summarySql =
                "INSERT INTO POS_DAILY_SUMMARY (SALE_DATE, TOTAL_COUNT, TOTAL_AMOUNT) " +
                        "SELECT SALE_DATE, COUNT(*), SUM(TOTAL_PRICE) " +
                        "FROM POS_SALES_DETAIL " +
                        "WHERE SALE_DATE = ? " +
                        "GROUP BY SALE_DATE " +
                        "ON DUPLICATE KEY UPDATE " +
                        "TOTAL_COUNT = VALUES(TOTAL_COUNT), " +
                        "TOTAL_AMOUNT = VALUES(TOTAL_AMOUNT)";

        try (PreparedStatement pstmt = conn.prepareStatement(summarySql)) {
            pstmt.setString(1, batchDate);
            pstmt.executeUpdate();
        }
    }

    // 최근 일일 집계 목록 (관리자 화면용, 최신순)
    // 마감 전 실시간 주문도 보이도록 POS_DAILY_SUMMARY가 아닌 상세 테이블에서 바로 집계
    public List<Map<String, Object>> selectRecentDailySummaries(Connection conn, int limit) throws SQLException {
        String sql = "SELECT SALE_DATE, COUNT(*) AS TOTAL_COUNT, SUM(TOTAL_PRICE) AS TOTAL_AMOUNT "
                + "FROM POS_SALES_DETAIL GROUP BY SALE_DATE ORDER BY SALE_DATE DESC LIMIT ?";
        List<Map<String, Object>> list = new ArrayList<>();

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("saleDate", rs.getString("SALE_DATE"));
                    row.put("totalCount", rs.getInt("TOTAL_COUNT"));
                    row.put("totalAmount", rs.getLong("TOTAL_AMOUNT"));
                    list.add(row);
                }
            }
        }
        return list;
    }

    // 상품 코드별 (수량, 총금액)을 조회하여 Map 형태로 반환
    public Map selectProductSalesMap(Connection conn, String batchDate) throws SQLException {
        String sql = "SELECT ITEM_CODE, SUM(QUANTITY) AS TOTAL_QTY, SUM(TOTAL_PRICE) AS TOTAL_AMT "
                + "FROM POS_SALES_DETAIL "
                + "WHERE SALE_DATE = ? "
                + "GROUP BY ITEM_CODE";

        Map resultMap = new HashMap();

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, batchDate);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String itemCode = rs.getString("ITEM_CODE");
                    int qty = rs.getInt("TOTAL_QTY");
                    long amt = rs.getLong("TOTAL_AMT");

                    resultMap.put(itemCode, new long[]{qty, amt});
                }
            }
        }
        return resultMap;
    }

    // [from, to) 사이 판매분 상품별 (수량, 금액) - 시간대 매출 CSV용
    // SALE_TIME: DATETIME(4) NOT NULL DEFAULT CURRENT_TIMESTAMP(4) (적재 시각 자동 기록)
    public Map<String, long[]> selectProductSalesBetween(Connection conn, LocalDateTime from, LocalDateTime to) throws SQLException {
        String sql = "SELECT ITEM_CODE, SUM(QUANTITY) AS TOTAL_QTY, SUM(TOTAL_PRICE) AS TOTAL_AMT "
                + "FROM POS_SALES_DETAIL WHERE SALE_TIME >= ? AND SALE_TIME < ? GROUP BY ITEM_CODE";
        Map<String, long[]> resultMap = new HashMap<>();

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, from);
            pstmt.setObject(2, to);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    resultMap.put(rs.getString("ITEM_CODE"), new long[]{rs.getLong("TOTAL_QTY"), rs.getLong("TOTAL_AMT")});
                }
            }
        }
        return resultMap;
    }
}
