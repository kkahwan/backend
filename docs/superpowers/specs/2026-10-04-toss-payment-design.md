# 토스페이먼츠 결제 설계 (2026-10-04)

포트폴리오용: 실제 판매 없이 **테스트 모드**로 결제위젯 연동 + 서버 승인 + 금액 위변조 검증 + 테스트를 보여줌.

## 결정

- 토스페이먼츠 **결제위젯 v2** (`https://js.tosspayments.com/v2/standard`), 테스트 키.
  - 키: `TOSS_CLIENT_KEY` / `TOSS_SECRET_KEY` 환경변수. 없으면 토스 문서용 공개 테스트 키 (`test_gck_docs_…` / `test_gsk_docs_…`).
- **로그인한 회원만 결제**. 비로그인이면 `#/login?next=#/checkout`.
- 기존 `POST /api/orders`(결제 없이 바로 판매 저장)는 **삭제** → 결제를 우회하는 길을 없앰. 판매 저장은 승인 후에만.
- 주문번호 = 토스 `orderId` = 기존 영수증 번호 앞부분(yyMMddHHmmss+4자리, 16자). 판매 행 `RECEIPT_NO` = 주문번호-NN (기존 배치/관리자 매출 그대로 동작).

## 흐름

```
장바구니 ─▶ #/checkout ── POST /api/payments/orders {상품코드: 수량}
                         ◀─ {orderId, orderName, amount, clientKey}   (금액은 서버가 DB 가격으로 계산, READY 저장)
           위젯 렌더 → [결제하기] → 토스 결제창 (테스트 카드)
토스 ─▶ /?payment=success&paymentKey&orderId&amount
           POST /api/payments/confirm {paymentKey, orderId, amount}
             1. 내 주문인지, 금액이 저장값과 같은지 확인 (다르면 400, 토스 호출 안 함)
             2. READY → PAYING 으로 선점 (동시/중복 승인 방지. 이미 DONE이면 저장된 결과 그대로 반환)
             3. 토스 승인 API POST /v1/payments/confirm (Basic secretKey:)
             4. 성공: DONE + POS_SALES_DETAIL 저장 (한 트랜잭션)
                DB 저장 실패: 토스 결제 취소 API로 되돌림 (돈만 빠지고 주문 없는 상황 방지) → FAILED
                토스 거절: FAILED + 토스 메시지 그대로 전달
토스 ─▶ /?payment=fail&code&message   (결제창 닫음/실패 → 장바구니 유지)
```

## 데이터 (`sql/shop_schema.sql`)

- `PAYMENT_ORDER(ORDER_ID PK, MEMBER_ID, ORDER_NAME, AMOUNT, STATUS READY|PAYING|DONE|FAILED, PAYMENT_KEY, METHOD, APPROVED_AT, FAIL_REASON, CREATED_AT)`
- `PAYMENT_ORDER_ITEM(ORDER_ID, LINE_NO, ITEM_CODE, ITEM_NAME, UNIT_PRICE, QUANTITY)` — 주문 시점 가격 스냅샷

## 코드

- `TossPaymentsClient`: 승인/취소 HTTP 호출만 (RestClient). 토스 오류 `{code, message}` → `TossPaymentException`
- `PaymentService`: 주문 생성, 승인(검증·선점·저장·보상취소)
- `OrderService`: 장바구니 검증·가격 계산(`price`)과 판매 행 저장(`saveSales`)으로 분리해 재사용
- `PaymentApiController`: `/api/payments/orders`, `/api/payments/confirm` (회원만)
- 프론트: `CheckoutPage`(위젯), `PaymentResultPage`(승인 요청·결과), 장바구니 버튼 → 결제하기

## 테스트

- `PaymentServiceTest` (토스 클라이언트는 Mockito 대역): 서버 가격으로 금액 계산 / 금액 위변조 시 토스 미호출 / 남의 주문 거부 / 중복 승인 시 토스 1회만 / 토스 거절 시 판매 미저장 / DB 저장 실패 시 결제 취소 호출
- `TossPaymentsClientTest` (MockRestServiceServer): Basic 인증 헤더, 요청 본문, 오류 응답 변환
- 수동 E2E: 테스트 카드로 실제 토스 테스트 결제창 승인

## 범위 밖

주문 내역 페이지, 부분 취소/환불 화면, 웹훅, 가상계좌.
