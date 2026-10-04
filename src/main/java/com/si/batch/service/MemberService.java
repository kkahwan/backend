package com.si.batch.service;

import com.si.batch.dao.MemberDao;
import com.si.batch.model.Member;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 회원가입 / 이메일 로그인 조회 / 구글 로그인 매칭.
 * 이메일은 아이디라 앞뒤 공백 제거 + 소문자로 맞춰 저장/조회
 */
@Service
public class MemberService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MIN_PASSWORD = 8;

    private final DataSource dataSource;
    private final PasswordEncoder passwordEncoder;
    private final MemberDao dao = new MemberDao();

    public MemberService(DataSource dataSource, PasswordEncoder passwordEncoder) {
        this.dataSource = dataSource;
        this.passwordEncoder = passwordEncoder;
    }

    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** @throws IllegalArgumentException 형식 오류(400) / IllegalStateException 이메일 중복(409) */
    public Member signup(String rawEmail, String password, String rawName) {
        String email = normalize(rawEmail);
        String name = rawName == null ? "" : rawName.trim();
        if (email.length() > 100 || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("이메일 형식을 확인해 주세요.");
        }
        if (password == null || password.length() < MIN_PASSWORD || password.length() > 72) { // BCrypt는 72바이트까지만 반영
            throw new IllegalArgumentException("비밀번호는 " + MIN_PASSWORD + "~72자로 입력해 주세요.");
        }
        if (name.isEmpty() || name.length() > 50) {
            throw new IllegalArgumentException("이름은 1~50자로 입력해 주세요.");
        }
        try (Connection conn = dataSource.getConnection()) {
            dao.insert(conn, email, passwordEncoder.encode(password), name, null);
            return dao.selectByEmail(conn, email);
        } catch (SQLException e) {
            // UNIQUE 위반 = SQLState 23xxx (MySQL/H2 공통)
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                throw new IllegalStateException("이미 가입된 이메일이에요.");
            }
            throw new RuntimeException("회원가입 처리 중 오류", e);
        }
    }

    public Member findByEmail(String email) {
        try (Connection conn = dataSource.getConnection()) {
            return dao.selectByEmail(conn, normalize(email));
        } catch (SQLException e) {
            throw new RuntimeException("회원 조회 중 오류", e);
        }
    }

    /**
     * 1) 구글 ID로 가입된 회원 2) 구글이 인증한 이메일과 같은 회원에 연결 3) 새로 가입 (비밀번호 없음)
     * @throws IllegalStateException 인증 안 된 이메일이 기존 회원과 겹칠 때 (남의 계정 연결 방지)
     */
    public Member loginWithGoogle(String googleId, String rawEmail, boolean emailVerified, String rawName) {
        String email = normalize(rawEmail);
        try (Connection conn = dataSource.getConnection()) {
            Member byGoogle = dao.selectByGoogleId(conn, googleId);
            if (byGoogle != null) return byGoogle;

            Member byEmail = dao.selectByEmail(conn, email);
            if (byEmail != null) {
                if (!emailVerified) throw new IllegalStateException("구글 계정 이메일이 인증되지 않았어요.");
                dao.updateGoogleId(conn, byEmail.memberId(), googleId);
                return dao.selectByGoogleId(conn, googleId);
            }

            String name = rawName == null || rawName.isBlank() ? email.substring(0, email.indexOf('@')) : rawName.trim();
            dao.insert(conn, email, null, name.length() > 50 ? name.substring(0, 50) : name, googleId);
            return dao.selectByGoogleId(conn, googleId);
        } catch (SQLException e) {
            throw new RuntimeException("구글 로그인 처리 중 오류", e);
        }
    }
}
