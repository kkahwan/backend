package com.si.batch.dao;

import com.si.batch.model.Banner;
import com.si.batch.model.Notice;
import com.si.batch.model.Product;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// 쇼핑몰 상품/공지/배너 (테이블 정의: sql/shop_schema.sql)
public class ShopDao {

    // 쇼핑몰용: 판매 중인 상품만, 최근 등록 순
    public List<Product> selectOnSaleProducts(Connection conn) throws SQLException {
        return selectProducts(conn, "WHERE ON_SALE = 1 ORDER BY CREATED_AT DESC, ITEM_CODE");
    }

    // 배치 리포트용: 판매 중지 상품도 매출 이력이 있을 수 있어 전부, 코드 순(파일 행 순서 고정)
    public List<Product> selectAllProducts(Connection conn) throws SQLException {
        return selectProducts(conn, "ORDER BY ITEM_CODE");
    }

    private List<Product> selectProducts(Connection conn, String tail) throws SQLException {
        String sql = "SELECT ITEM_CODE, ITEM_NAME, UNIT_PRICE, DESCRIPTION, CREATED_AT FROM PRODUCT " + tail;
        List<Product> list = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                list.add(new Product(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getObject(5, LocalDateTime.class)));
            }
        }
        return list;
    }

    // 고정 공지 먼저, 그다음 최신순
    public List<Notice> selectNotices(Connection conn, int limit) throws SQLException {
        String sql = "SELECT NOTICE_ID, TITLE, CONTENT, PINNED, CREATED_AT FROM NOTICE "
                + "ORDER BY PINNED DESC, CREATED_AT DESC, NOTICE_ID DESC LIMIT ?";
        List<Notice> list = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new Notice(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                            rs.getObject(5, LocalDateTime.class)));
                }
            }
        }
        return list;
    }

    // 쇼핑몰용: 노출 기간 안의 배너만, 정한 순서대로
    public List<Banner> selectActiveBanners(Connection conn) throws SQLException {
        return selectBanners(conn, "WHERE START_AT <= NOW() AND (END_AT IS NULL OR END_AT > NOW()) ORDER BY SORT_ORDER, BANNER_ID");
    }

    // 관리자용: 지난 배너/예약 배너 포함 전부
    public List<Banner> selectAllBanners(Connection conn) throws SQLException {
        return selectBanners(conn, "ORDER BY SORT_ORDER, BANNER_ID");
    }

    private List<Banner> selectBanners(Connection conn, String tail) throws SQLException {
        String sql = "SELECT BANNER_ID, TITLE, SUBTITLE, LINK_URL, LINK_TEXT, SORT_ORDER, START_AT, END_AT FROM BANNER " + tail;
        List<Banner> list = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                list.add(new Banner(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6), rs.getObject(7, LocalDateTime.class), rs.getObject(8, LocalDateTime.class)));
            }
        }
        return list;
    }

    public void insertBanner(Connection conn, Banner b) throws SQLException {
        String sql = "INSERT INTO BANNER (TITLE, SUBTITLE, LINK_URL, LINK_TEXT, SORT_ORDER, START_AT, END_AT) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            bindBanner(pstmt, b);
            pstmt.executeUpdate();
        }
    }

    // 반환: 바뀐 행 수 (0이면 없는 배너)
    public int updateBanner(Connection conn, int bannerId, Banner b) throws SQLException {
        String sql = "UPDATE BANNER SET TITLE = ?, SUBTITLE = ?, LINK_URL = ?, LINK_TEXT = ?, SORT_ORDER = ?, START_AT = ?, END_AT = ? WHERE BANNER_ID = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            bindBanner(pstmt, b);
            pstmt.setInt(8, bannerId);
            return pstmt.executeUpdate();
        }
    }

    public int deleteBanner(Connection conn, int bannerId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM BANNER WHERE BANNER_ID = ?")) {
            pstmt.setInt(1, bannerId);
            return pstmt.executeUpdate();
        }
    }

    private void bindBanner(PreparedStatement pstmt, Banner b) throws SQLException {
        pstmt.setString(1, b.title());
        pstmt.setString(2, b.subtitle());
        pstmt.setString(3, b.linkUrl());
        pstmt.setString(4, b.linkText());
        pstmt.setInt(5, b.sortOrder());
        pstmt.setObject(6, b.startAt());
        pstmt.setObject(7, b.endAt());
    }
}
