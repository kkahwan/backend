package com.si.batch.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnWebApplication // 배치 1회 실행(Main, 웹서버 없음)에서는 제외
public class SecurityConfig {

    // 쇼핑 API는 누구나, 관리자 API는 로그인 필요 (화면은 E:\job\batch-frontend Svelte 앱)
    // CSRF는 기본값 유지 -> 프론트가 /api/csrf 로 토큰을 받아 POST 헤더에 실어 보냄
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
