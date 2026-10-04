package com.si.batch.controller;

import com.si.batch.model.Member;
import com.si.batch.service.MemberService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// 회원가입/내 정보. 로그인(/api/auth/login)·로그아웃(/api/auth/logout)·구글 로그인은 SecurityConfig
@RestController
public class AuthApiController {

    public record SignupRequest(String email, String password, String name) {}
    public record MemberView(String email, String name, boolean google) {}
    // member: 비로그인/관리자면 null. google: 구글 로그인 버튼을 보여줄지 (GOOGLE_CLIENT_ID 설정 여부)
    public record Me(MemberView member, boolean google) {}

    private final MemberService members;
    private final ObjectProvider<ClientRegistrationRepository> google;

    public AuthApiController(MemberService members, ObjectProvider<ClientRegistrationRepository> google) {
        this.members = members;
        this.google = google;
    }

    // 가입만 하고, 로그인은 프론트가 이어서 /api/auth/login 호출 (세션/CSRF 처리를 Spring 로그인 흐름 하나로)
    @PostMapping("/api/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberView signup(@RequestBody SignupRequest req) {
        return view(members.signup(req.email(), req.password(), req.name()));
    }

    // 비로그인이어도 200 (401이면 브라우저 Basic 로그인 창이 뜸)
    @GetMapping("/api/auth/me")
    public Me me(Authentication auth) {
        Member m = auth == null || auth instanceof AnonymousAuthenticationToken ? null : members.findByEmail(auth.getName());
        return new Me(m == null ? null : view(m), google.getIfAvailable() != null);
    }

    private static MemberView view(Member m) {
        return new MemberView(m.email(), m.name(), m.googleId() != null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }
}
