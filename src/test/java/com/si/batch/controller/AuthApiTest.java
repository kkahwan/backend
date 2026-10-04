package com.si.batch.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM MEMBER");
    }

    private void signup(String email, String password, int expected) throws Exception {
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","name":"하늘"}""".formatted(email, password)))
                .andExpect(status().is(expected));
    }

    private MockHttpSession login(String email, String password, int expected) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .param("email", email).param("password", password))
                .andExpect(status().is(expected))
                .andReturn().getRequest().getSession(false);
    }

    @Test
    void 가입하고_로그인하면_내정보가_보인다() throws Exception {
        signup("sky@example.com", "password1", 201);
        MockHttpSession session = login("sky@example.com", "password1", 200);

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.email").value("sky@example.com"))
                .andExpect(jsonPath("$.member.name").value("하늘"));
    }

    @Test
    void 이메일은_대소문자와_공백을_무시한다() throws Exception {
        signup("  Sky@Example.com ", "password1", 201);
        login("sky@example.com", "password1", 200);
    }

    @Test
    void 같은_이메일로_다시_가입하면_409() throws Exception {
        signup("sky@example.com", "password1", 201);
        signup("SKY@example.com", "password2", 409);
    }

    @Test
    void 이메일_형식이나_짧은_비밀번호는_400() throws Exception {
        signup("not-an-email", "password1", 400);
        signup("sky@example.com", "short", 400);
    }

    @Test
    void 비밀번호가_틀리면_401이고_Basic_팝업을_띄우지_않는다() throws Exception {
        signup("sky@example.com", "password1", 201);
        mvc.perform(post("/api/auth/login").with(csrf()).param("email", "sky@example.com").param("password", "wrong-pw"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void 비로그인이면_member는_null이고_구글_사용_여부를_알려준다() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member").isEmpty())
                .andExpect(jsonPath("$.google").value(true));
    }

    @Test
    void 로그아웃하면_세션이_끊긴다() throws Exception {
        signup("sky@example.com", "password1", 201);
        MockHttpSession session = login("sky@example.com", "password1", 200);

        mvc.perform(post("/api/auth/logout").with(csrf()).session(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.member").isEmpty());
    }

    @Test
    void 회원은_관리자_API에_들어갈_수_없다() throws Exception {
        mvc.perform(get("/api/admin/banners").with(user("sky@example.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 관리자는_Basic_로그인으로_관리자_API를_쓴다() throws Exception {
        mvc.perform(get("/api/admin/banners")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/banners").with(httpBasic("admin", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/banners").with(httpBasic("admin", "admin-pw"))).andExpect(status().isOk());
    }
}
