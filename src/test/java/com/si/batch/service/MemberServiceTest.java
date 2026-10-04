package com.si.batch.service;

import com.si.batch.model.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 구글 로그인 시 회원 매칭 규칙 (실제 구글 호출 없이 sub/email만 넣어 확인)
@SpringBootTest
@ActiveProfiles("test")
class MemberServiceTest {

    @Autowired MemberService members;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM MEMBER");
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM MEMBER", Integer.class);
    }

    @Test
    void 처음_보는_구글_계정은_비밀번호_없이_가입된다() {
        Member m = members.loginWithGoogle("g-1", "Sky@Gmail.com", true, "하늘");

        assertEquals("sky@gmail.com", m.email());
        assertEquals("g-1", m.googleId());
        assertNull(m.passwordHash());
        assertEquals(1, count());
    }

    @Test
    void 같은_구글_계정으로_다시_오면_같은_회원이다() {
        Member first = members.loginWithGoogle("g-1", "sky@gmail.com", true, "하늘");
        Member again = members.loginWithGoogle("g-1", "sky@gmail.com", true, "하늘");

        assertEquals(first.memberId(), again.memberId());
        assertEquals(1, count());
    }

    @Test
    void 인증된_이메일이_기존_회원과_같으면_그_회원에_연결된다() {
        Member local = members.signup("sky@gmail.com", "password1", "하늘");
        Member google = members.loginWithGoogle("g-1", "sky@gmail.com", true, "구글이름");

        assertEquals(local.memberId(), google.memberId());
        assertEquals("g-1", google.googleId());
        assertNotNull(google.passwordHash()); // 이메일 로그인도 계속 됨
        assertEquals("하늘", google.name());    // 기존 이름 유지
        assertEquals(1, count());
    }

    @Test
    void 인증_안된_이메일이면_기존_회원에_연결하지_않는다() {
        members.signup("sky@gmail.com", "password1", "하늘");

        assertThrows(IllegalStateException.class,
                () -> members.loginWithGoogle("g-1", "sky@gmail.com", false, "누군가"));
        assertEquals(1, count());
    }
}
