# 회원 로그인/회원가입 설계 (2026-10-04)

포트폴리오용 쇼핑몰(하늘마켓)에 회원 기능 추가. 다음 단계인 결제(토스페이먼츠 테스트 모드)에서 주문을 회원에 연결하기 위한 기반.

## 결정

- 인증: **세션(JSESSIONID)**. 프론트는 Vite 프록시로 같은 출처, CSRF는 기존 `/api/csrf` 방식 유지. JWT는 서버 1대라 얻는 게 없어 제외.
- 이메일 = 아이디. 비밀번호 BCrypt, 8자 이상.
- 구글: Spring Security OAuth2 Client (OIDC). `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` 없으면 구글 로그인만 꺼짐(서버는 정상 기동).
- 관리자: 기존 환경변수 계정 + HTTP Basic 유지, 권한 `ADMIN`. `/api/admin/**`은 `ADMIN`만. 회원은 `USER`.

## 데이터

`MEMBER(MEMBER_ID PK, EMAIL UNIQUE, PASSWORD_HASH NULL, NAME, GOOGLE_ID UNIQUE NULL, CREATED_AT)` — `sql/shop_schema.sql`

## API

| 메서드 | 경로 | 동작 |
|---|---|---|
| POST | `/api/auth/signup` | JSON `{email,password,name}` → 201. 형식 오류 400, 중복 409 |
| POST | `/api/auth/login` | form `email,password` (Spring formLogin) → 200 / 401 |
| POST | `/api/auth/logout` | 204 |
| GET | `/api/auth/me` | `{member: {email,name}|null, google: boolean}` 항상 200 (401이면 Basic 팝업이 떠서) |
| GET | `/oauth2/authorization/google` | 구글 로그인 시작 → 성공 시 `/`, 실패 시 `/#/login?error=google` |

가입 후 자동 로그인은 프론트가 이어서 `/api/auth/login` 호출.

## 구글 계정 매칭

1. `GOOGLE_ID`(sub)로 찾음 → 로그인
2. 없고 구글이 인증한 이메일이 기존 회원과 같으면 → 그 회원에 `GOOGLE_ID` 연결
3. 그것도 없으면 → 비밀번호 없는 회원으로 가입

콜백: `{baseUrl}/login/oauth2/code/google` → 개발 시 `http://localhost:5173/login/oauth2/code/google` (Vite 프록시에 `/oauth2`, `/login/oauth2` 추가).

### Google Cloud 설정 (사용자)

1. console.cloud.google.com → 프로젝트 생성 → API 및 서비스 → OAuth 동의 화면(외부, 테스트 사용자에 본인 추가)
2. 사용자 인증 정보 → OAuth 클라이언트 ID → 웹 애플리케이션
3. 승인된 리디렉션 URI: `http://localhost:5173/login/oauth2/code/google`
4. `setx GOOGLE_CLIENT_ID ...`, `setx GOOGLE_CLIENT_SECRET ...` 후 IntelliJ 재시작

## 프론트

`#/login`(이메일 로그인 + Google로 계속하기), `#/signup`, 헤더에 로그인/○○님·로그아웃. `stores/auth.svelte.ts`.

## 테스트

JUnit + MockMvc + H2(MySQL 모드), `mvn test`로 MySQL 없이 실행:
가입 성공 / 중복 409 / 형식 오류 400 / 로그인 성공·실패 / `/me` / 회원의 관리자 API 403 / 구글 매칭 3가지.

## 범위 밖

이메일 인증, 비밀번호 찾기, 로그인 시도 제한, 회원 탈퇴.
