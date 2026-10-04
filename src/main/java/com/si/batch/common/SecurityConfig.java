package com.si.batch.common;

import com.si.batch.model.Member;
import com.si.batch.service.MemberService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;

import java.util.List;

@Configuration
@ConditionalOnWebApplication // 배치 1회 실행(Main, 웹서버 없음)에서는 제외
public class SecurityConfig {

    // 쇼핑 API는 누구나, 관리자 API는 ADMIN만 (화면은 E:\job\batch-frontend Svelte 앱)
    // 회원: 세션 로그인(이메일/비밀번호 또는 구글), 관리자: 기존대로 HTTP Basic
    // CSRF는 기본값 유지 -> 프론트가 /api/csrf 로 토큰을 받아 POST 헤더에 실어 보냄
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, MemberService members,
                                           ObjectProvider<ClientRegistrationRepository> google) throws Exception {
        BasicAuthenticationEntryPoint basic = new BasicAuthenticationEntryPoint();
        basic.setRealmName("haneul-admin");

        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults())
                // 로그인이 필요한 곳은 관리자 API뿐 -> 401이면 브라우저 Basic 로그인 창 (로그인 페이지로 리다이렉트하지 않음)
                .exceptionHandling(e -> e.authenticationEntryPoint(basic))
                // 회원 로그인: form(email, password) -> 200/401. 리다이렉트 대신 상태코드만 (WWW-Authenticate 없어 Basic 팝업 안 뜸)
                .formLogin(f -> f.loginPage("/#/login").loginProcessingUrl("/api/auth/login").usernameParameter("email")
                        .successHandler((req, res, a) -> res.setStatus(HttpStatus.OK.value()))
                        .failureHandler((req, res, ex) -> res.setStatus(HttpStatus.UNAUTHORIZED.value())))
                .logout(l -> l.logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));

        if (google.getIfAvailable() != null) {
            http.oauth2Login(o -> o.loginPage("/#/login")
                    .userInfoEndpoint(u -> u.oidcUserService(googleUserService(members)))
                    .defaultSuccessUrl("/", true)
                    .failureUrl("/#/login?error=google"));
        }
        return http.build();
    }

    // 관리자(환경변수 계정. 이메일 형식이 아니라 회원과 겹치지 않음) 또는 회원(이메일)
    @Bean
    public UserDetailsService userDetailsService(MemberService members, PasswordEncoder encoder,
                                                 @Value("${spring.security.user.name}") String adminName,
                                                 @Value("${spring.security.user.password}") String adminPassword) {
        String adminHash = encoder.encode(adminPassword);
        return username -> {
            if (adminName.equals(username)) {
                return User.withUsername(adminName).password(adminHash).roles("ADMIN").build();
            }
            Member m = members.findByEmail(username);
            if (m == null || m.passwordHash() == null) { // 구글로만 가입한 회원은 비밀번호 로그인 불가
                throw new UsernameNotFoundException("회원 없음");
            }
            return User.withUsername(m.email()).password(m.passwordHash()).roles("USER").build();
        };
    }

    // GOOGLE_CLIENT_ID 환경변수가 있을 때만 구글 로그인 활성화 (없어도 서버는 뜸)
    @Bean
    @ConditionalOnExpression("'${GOOGLE_CLIENT_ID:}' != ''")
    public ClientRegistrationRepository googleClientRegistration(@Value("${GOOGLE_CLIENT_ID}") String clientId,
                                                                @Value("${GOOGLE_CLIENT_SECRET}") String clientSecret) {
        // 콜백: {baseUrl}/login/oauth2/code/google (개발 시 Vite 5173 경유), scope: openid profile email
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId).clientSecret(clientSecret).build());
    }

    // 구글 사용자 -> MEMBER 매칭. 이름(getName)을 email 클레임으로 맞춰 /api/auth/me 가 이메일 로그인과 같은 방식으로 조회
    private OAuth2UserService<OidcUserRequest, OidcUser> googleUserService(MemberService members) {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser g = delegate.loadUser(request);
            try {
                members.loginWithGoogle(g.getSubject(), g.getEmail(), Boolean.TRUE.equals(g.getEmailVerified()), g.getFullName());
            } catch (IllegalStateException e) {
                throw new OAuth2AuthenticationException(new OAuth2Error("member_link_denied"), e.getMessage());
            }
            return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), g.getIdToken(), g.getUserInfo(), "email");
        };
    }
}
