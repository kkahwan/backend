package com.si.batch.service;

import com.si.batch.common.TossPaymentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 토스 API에 보내는 HTTP 요청 모양과 응답/오류 변환 (가짜 서버로 확인, 네트워크 없음)
class TossPaymentsClientTest {

    static final String URL = "https://api.test";
    static final String BASIC = "Basic " + Base64.getEncoder().encodeToString("test_sk:".getBytes(StandardCharsets.UTF_8));

    MockRestServiceServer server;
    TossPaymentsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentsClient(builder, URL, "test_sk");
    }

    @Test
    void 승인_요청은_시크릿키_Basic_인증과_주문정보를_보낸다() {
        server.expect(requestTo(URL + "/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", BASIC))
                .andExpect(content().json("""
                        {"paymentKey":"pk-1","orderId":"2610041500001234","amount":4500}"""))
                .andRespond(withSuccess("""
                        {"paymentKey":"pk-1","orderId":"2610041500001234","status":"DONE","totalAmount":4500,
                         "method":"카드","approvedAt":"2026-10-04T15:00:00+09:00","card":{"number":"4330****"}}""",
                        MediaType.APPLICATION_JSON));

        TossPaymentsClient.Payment p = client.confirm("pk-1", "2610041500001234", 4500);

        assertEquals("DONE", p.status());
        assertEquals(4500, p.totalAmount());
        assertEquals("카드", p.method());
        server.verify();
    }

    @Test
    void 토스_오류_응답은_코드와_메시지로_바뀐다() {
        server.expect(requestTo(URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"ALREADY_PROCESSED_PAYMENT","message":"이미 처리된 결제 입니다."}"""));

        TossPaymentException e = assertThrows(TossPaymentException.class, () -> client.confirm("pk-1", "o-1", 100));

        assertEquals("ALREADY_PROCESSED_PAYMENT", e.getCode());
        assertEquals("이미 처리된 결제 입니다.", e.getMessage());
    }

    @Test
    void 취소_요청은_결제키_경로와_사유를_보낸다() {
        server.expect(requestTo(URL + "/v1/payments/pk-1/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", BASIC))
                .andExpect(content().json("""
                        {"cancelReason":"주문 저장 실패"}"""))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.cancel("pk-1", "주문 저장 실패");
        server.verify();
    }
}
