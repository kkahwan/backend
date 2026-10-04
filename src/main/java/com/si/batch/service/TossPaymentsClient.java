package com.si.batch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.batch.common.TossPaymentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;

/**
 * 토스페이먼츠 코어 API 호출만 담당 (승인/취소). 인증: Basic base64("시크릿키:")
 * https://docs.tosspayments.com/reference#결제-승인
 * 타임아웃: application.properties 의 spring.http.client.* (Boot가 만든 RestClient.Builder에 적용)
 */
@Component
public class TossPaymentsClient {

    // 토스 Payment 객체 중 쓰는 필드만 (나머지는 무시)
    public record Payment(String paymentKey, String orderId, String status, long totalAmount, String method,
                          OffsetDateTime approvedAt) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;

    public TossPaymentsClient(RestClient.Builder builder,
                              @Value("${toss.api-url:https://api.tosspayments.com}") String apiUrl,
                              @Value("${toss.secret-key}") String secretKey) {
        String basic = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.http = builder.baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                // 4xx/5xx 응답 본문 {code, message} -> TossPaymentException
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                    JsonNode body = JSON.readTree(res.getBody());
                    throw new TossPaymentException(body.path("code").asText("UNKNOWN"),
                            body.path("message").asText("결제 서버 오류 (HTTP " + res.getStatusCode().value() + ")"));
                })
                .build();
    }

    /** 결제창 인증 후 10분 안에 호출해야 승인됨 */
    public Payment confirm(String paymentKey, String orderId, long amount) {
        return http.post().uri("/v1/payments/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
                .retrieve()
                .body(Payment.class);
    }

    /** 전액 취소 */
    public void cancel(String paymentKey, String reason) {
        http.post().uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cancelReason", reason))
                .retrieve()
                .toBodilessEntity();
    }
}
