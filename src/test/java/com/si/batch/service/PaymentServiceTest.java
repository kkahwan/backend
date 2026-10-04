package com.si.batch.service;

import com.si.batch.common.TossPaymentException;
import com.si.batch.service.PaymentService.Checkout;
import com.si.batch.service.PaymentService.Result;
import com.si.batch.service.TossPaymentsClient.Payment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 결제 흐름 규칙. 토스 API는 대역(Mockito)으로 바꿔 실제 호출 없이 확인
@SpringBootTest
@ActiveProfiles("test")
class PaymentServiceTest {

    static final long ME = 1, OTHER = 2;

    @Autowired PaymentService payments;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TossPaymentsClient toss;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM PAYMENT_ORDER_ITEM");
        jdbc.update("DELETE FROM PAYMENT_ORDER");
        jdbc.update("DELETE FROM POS_SALES_DETAIL");
        jdbc.update("DELETE FROM PRODUCT");
        jdbc.update("INSERT INTO PRODUCT (ITEM_CODE, ITEM_NAME, UNIT_PRICE) VALUES ('A', '사과', 1000), ('B', '배', 2500)");
        jdbc.update("INSERT INTO PRODUCT (ITEM_CODE, ITEM_NAME, UNIT_PRICE, ON_SALE) VALUES ('X', '판매중지', 500, 0)");
    }

    private Checkout order() throws Exception {
        Map<String, Integer> cart = new LinkedHashMap<>();
        cart.put("A", 2);
        cart.put("B", 1);
        return payments.createOrder(ME, cart);
    }

    private void tossApproves(String orderId, long amount) {
        when(toss.confirm(anyString(), eq(orderId), eq(amount)))
                .thenReturn(new Payment("pk-1", orderId, "DONE", amount, "카드", OffsetDateTime.parse("2026-10-04T15:00:00+09:00")));
    }

    private int sales() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM POS_SALES_DETAIL", Integer.class);
    }

    private String status(String orderId) {
        return jdbc.queryForObject("SELECT STATUS FROM PAYMENT_ORDER WHERE ORDER_ID = ?", String.class, orderId);
    }

    @Test
    void 주문금액은_서버가_DB_가격으로_계산한다() throws Exception {
        Checkout c = order();

        assertEquals(1000 * 2 + 2500, c.amount());
        assertEquals("사과 외 1건", c.orderName());
        assertEquals(16, c.orderId().length());
        assertEquals("READY", status(c.orderId()));
    }

    @Test
    void 판매중지_상품이나_빈_장바구니는_주문할_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> payments.createOrder(ME, Map.of("X", 1)));
        assertThrows(IllegalArgumentException.class, () -> payments.createOrder(ME, Map.of()));
    }

    @Test
    void 승인되면_주문이_완료되고_판매_행이_저장된다() throws Exception {
        Checkout c = order();
        tossApproves(c.orderId(), 4500);

        Result r = payments.confirm(ME, "pk-1", c.orderId(), 4500);

        assertEquals("카드", r.method());
        assertEquals("DONE", status(c.orderId()));
        assertEquals(2, sales());
        assertEquals(2000, jdbc.queryForObject(
                "SELECT TOTAL_PRICE FROM POS_SALES_DETAIL WHERE RECEIPT_NO = ?", Integer.class, c.orderId() + "-01"));
    }

    @Test
    void 결제금액이_주문금액과_다르면_토스를_호출하지_않는다() throws Exception {
        Checkout c = order();

        assertThrows(IllegalArgumentException.class, () -> payments.confirm(ME, "pk-1", c.orderId(), 100));
        verify(toss, never()).confirm(anyString(), anyString(), anyLong());
        assertEquals("READY", status(c.orderId()));
    }

    @Test
    void 다른_회원의_주문은_승인할_수_없다() throws Exception {
        Checkout c = order();

        assertThrows(IllegalArgumentException.class, () -> payments.confirm(OTHER, "pk-1", c.orderId(), 4500));
        verify(toss, never()).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    void 같은_결제를_두번_승인해도_토스는_한번만_호출된다() throws Exception {
        Checkout c = order();
        tossApproves(c.orderId(), 4500);

        payments.confirm(ME, "pk-1", c.orderId(), 4500);
        Result again = payments.confirm(ME, "pk-1", c.orderId(), 4500); // 새로고침 등

        assertEquals(c.orderId(), again.orderId());
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong());
        assertEquals(2, sales());
    }

    @Test
    void 토스가_거절하면_실패로_남고_판매는_저장하지_않는다() throws Exception {
        Checkout c = order();
        when(toss.confirm(anyString(), anyString(), anyLong()))
                .thenThrow(new TossPaymentException("REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다."));

        TossPaymentException e = assertThrows(TossPaymentException.class, () -> payments.confirm(ME, "pk-1", c.orderId(), 4500));

        assertEquals("REJECT_CARD_PAYMENT", e.getCode());
        assertEquals("FAILED", status(c.orderId()));
        assertEquals(0, sales());
    }

    @Test
    void 승인_후_주문_저장에_실패하면_결제를_취소한다() throws Exception {
        Checkout c = order();
        tossApproves(c.orderId(), 4500);
        // 같은 영수증 번호를 미리 넣어 판매 행 저장을 실패시킴
        jdbc.update("INSERT INTO POS_SALES_DETAIL (RECEIPT_NO, SALE_DATE, ITEM_CODE, ITEM_NAME, UNIT_PRICE, QUANTITY, TOTAL_PRICE) "
                + "VALUES (?, '20261004', 'A', '사과', 1000, 1, 1000)", c.orderId() + "-01");

        assertThrows(IllegalStateException.class, () -> payments.confirm(ME, "pk-1", c.orderId(), 4500));

        verify(toss).cancel(eq("pk-1"), anyString());
        assertEquals("FAILED", status(c.orderId()));
        assertEquals(1, sales()); // 미리 넣은 1행만 (롤백됨)
    }
}
