package com.si.batch.dao;

import com.si.batch.model.Member;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

// 회원 (테이블 정의: sql/shop_schema.sql)
public class MemberDao {

    private static final String COLUMNS = "SELECT MEMBER_ID, EMAIL, PASSWORD_HASH, NAME, GOOGLE_ID, CREATED_AT FROM MEMBER ";

    public Member selectByEmail(Connection conn, String email) throws SQLException {
        return selectOne(conn, COLUMNS + "WHERE EMAIL = ?", email);
    }

    public Member selectByGoogleId(Connection conn, String googleId) throws SQLException {
        return selectOne(conn, COLUMNS + "WHERE GOOGLE_ID = ?", googleId);
    }

    // 이메일 중복이면 SQLIntegrityConstraintViolationException (UNIQUE)
    public void insert(Connection conn, String email, String passwordHash, String name, String googleId) throws SQLException {
        String sql = "INSERT INTO MEMBER (EMAIL, PASSWORD_HASH, NAME, GOOGLE_ID) VALUES (?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, email);
            pstmt.setString(2, passwordHash);
            pstmt.setString(3, name);
            pstmt.setString(4, googleId);
            pstmt.executeUpdate();
        }
    }

    public void updateGoogleId(Connection conn, long memberId, String googleId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("UPDATE MEMBER SET GOOGLE_ID = ? WHERE MEMBER_ID = ?")) {
            pstmt.setString(1, googleId);
            pstmt.setLong(2, memberId);
            pstmt.executeUpdate();
        }
    }

    private Member selectOne(Connection conn, String sql, String value) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, value);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next()
                        ? new Member(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                rs.getObject(6, LocalDateTime.class))
                        : null;
            }
        }
    }
}
