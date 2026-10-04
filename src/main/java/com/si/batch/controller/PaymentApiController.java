package com.si.batch.controller;

import com.si.batch.common.TossPaymentException;
import com.si.batch.model.Member;
import com.si.batch.service.MemberService;
import com.si.batch.service.PaymentService;
import com.si.batch.service.PaymentService.Checkout;
import com.si.batch.service.PaymentService.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

// 토스페이먼츠 결제 (로그인한 회원만). 흐름은 PaymentService
@RestController
public class PaymentApiController {

    public record ConfirmRequest(String paymentKey, String orderId, long amount) {}

    private final PaymentService payments;
    private final MemberService members;

    public PaymentApiController(PaymentService payments, MemberService members) {
        this.payments = payments;
        this.members = members;
    }

    // 요청 본문: {"ITM_HS_01": 2, "ITM_HS_04": 1} -> 서버가 계산한 금액/주문번호 + 결제위젯 클라이언트 키
    @PostMapping("/api/payments/orders")
    public Checkout createOrder(Authentication auth, @RequestBody Map<String, Integer> cart) throws Exception {
        return payments.createOrder(member(auth).memberId(), cart);
    }

    // 결제창 successUrl로 받은 paymentKey/orderId/amount -> 최종 승인
    @PostMapping("/api/payments/confirm")
    public Result confirm(Authentication auth, @RequestBody ConfirmRequest req) throws Exception {
        if (req.paymentKey() == null || req.orderId() == null) throw new IllegalArgumentException("결제 정보가 없어요.");
        return payments.confirm(member(auth).memberId(), req.paymentKey(), req.orderId(), req.amount());
    }

    // 401은 직접 응답 (Security 인증 실패로 두면 관리자용 Basic 로그인 창이 뜸)
    private Member member(Authentication auth) {
        Member m = auth == null || auth instanceof AnonymousAuthenticationToken ? null : members.findByEmail(auth.getName());
        if (m == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요해요.");
        return m;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", String.valueOf(e.getReason())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    // 토스 거절 사유(카드 한도 등)는 손님에게 그대로 보여줌
    @ExceptionHandler(TossPaymentException.class)
    public ResponseEntity<Map<String, String>> tossRejected(TossPaymentException e) {
        return ResponseEntity.badRequest().body(Map.of("code", e.getCode(), "message", e.getMessage()));
    }
}
