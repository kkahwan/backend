package com.si.batch.service;

import com.si.batch.common.TossPaymentException;
import com.si.batch.dao.PaymentOrderDao;
import com.si.batch.model.PaymentOrder;
import com.si.batch.service.OrderService.Line;
import com.si.batch.service.TossPaymentsClient.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 토스페이먼츠 결제 흐름 (설계: docs/superpowers/specs/2026-10-04-toss-payment-design.md)
 * 1) createOrder: 서버 가격으로 금액 계산 후 READY 저장 -> 프론트가 결제위젯에 그 금액/주문번호를 넘김
 * 2) confirm: 금액 위변조 확인 -> READY를 PAYING으로 선점 -> 토스 승인 -> 판매 저장 + DONE (실패 시 결제 취소)
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public record Checkout(String orderId, String orderName, long amount, String clientKey) {}
    public record Result(String orderId, String orderName, long amount, String method, LocalDateTime approvedAt) {}

    private final DataSource dataSource;
    private final OrderService orders;
    private final TossPaymentsClient toss;
    private final String clientKey;
    private final PaymentOrderDao dao = new PaymentOrderDao();

    public PaymentService(DataSource dataSource, OrderService orders, TossPaymentsClient toss,
                          @Value("${toss.client-key}") String clientKey) {
        this.dataSource = dataSource;
        this.orders = orders;
        this.toss = toss;
        this.clientKey = clientKey;
    }

    /** @throws IllegalArgumentException 빈 장바구니 / 없는 상품 / 수량 범위 */
    public Checkout createOrder(long memberId, Map<String, Integer> cart) throws SQLException {
        List<Line> lines = orders.price(cart);
        long amount = lines.stream().mapToLong(Line::amount).sum();
        String orderName = lines.size() == 1 ? lines.get(0).itemName()
                : lines.get(0).itemName() + " 외 " + (lines.size() - 1) + "건";
        if (orderName.length() > 100) orderName = orderName.substring(0, 100);

        String orderId = OrderService.newOrderNo(LocalDateTime.now(SEOUL));
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                dao.insert(conn, orderId, memberId, orderName, amount, lines);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
        return new Checkout(orderId, orderName, amount, clientKey);
    }

    /**
     * 결제창 인증 후 successUrl로 돌아온 값으로 최종 승인.
     * @throws IllegalArgumentException 내 주문이 아님 / 금액 불일치 / 이미 실패한 주문 (400)
     * @throws IllegalStateException 승인 처리 중 / 주문 저장 실패로 결제 취소됨 (409)
     * @throws TossPaymentException 토스가 승인을 거절 (카드 한도 등)
     */
    public Result confirm(long memberId, String paymentKey, String orderId, long amount) throws SQLException {
        PaymentOrder order;
        List<Line> lines;
        try (Connection conn = dataSource.getConnection()) {
            order = dao.select(conn, orderId);
            if (order == null || order.memberId() != memberId) {
                throw new IllegalArgumentException("주문을 찾을 수 없어요.");
            }
            // 새로고침 등으로 같은 결제를 다시 승인 요청 -> 토스를 다시 부르지 않고 저장된 결과 반환
            if ("DONE".equals(order.status())) {
                if (!order.paymentKey().equals(paymentKey)) throw new IllegalArgumentException("이미 결제된 주문이에요.");
                return result(order);
            }
            // 결제창으로 넘어간 금액이 조작됐는지: 서버에 저장한 주문 금액과 비교 (토스 호출 전에 차단)
            if (order.amount() != amount) {
                throw new IllegalArgumentException("결제 금액이 주문 금액과 달라요.");
            }
            if ("FAILED".equals(order.status())) {
                throw new IllegalArgumentException("이미 실패한 주문이에요. 장바구니에서 다시 결제해 주세요.");
            }
            // 동시에 두 번 요청이 와도 한 쪽만 토스 승인으로 진행
            if (!dao.changeStatus(conn, orderId, "READY", "PAYING")) {
                throw new IllegalStateException("결제를 처리하고 있어요. 잠시 후 다시 확인해 주세요.");
            }
            lines = dao.selectItems(conn, orderId);
        }

        Payment paid;
        try {
            paid = toss.confirm(paymentKey, orderId, order.amount());
        } catch (TossPaymentException e) {
            fail(orderId, paymentKey, e.getCode() + ": " + e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            // ponytail: 네트워크 오류면 토스 쪽 승인 여부를 모름 -> READY로 되돌려 재시도 허용.
            // 재시도 때 ALREADY_PROCESSED_PAYMENT가 오면 FAILED로 남음. 필요하면 결제 조회 API(GET /v1/payments/{paymentKey})로 확인 추가
            try (Connection conn = dataSource.getConnection()) {
                dao.changeStatus(conn, orderId, "PAYING", "READY");
            }
            throw e;
        }

        LocalDateTime approvedAt = paid.approvedAt() == null ? LocalDateTime.now(SEOUL)
                : paid.approvedAt().atZoneSameInstant(SEOUL).toLocalDateTime();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (paid.totalAmount() != order.amount()) {
                    throw new IllegalStateException("승인 금액 불일치: " + paid.totalAmount());
                }
                orders.saveSales(conn, orderId, lines, approvedAt);
                dao.markDone(conn, orderId, paymentKey, paid.method(), approvedAt);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            // 돈은 빠졌는데 주문이 없는 상태를 남기지 않도록 결제 취소 (보상 처리)
            log.error("[결제] 승인 후 주문 저장 실패 -> 결제 취소 orderId={}", orderId, e);
            try {
                toss.cancel(paymentKey, "주문 저장 실패로 자동 취소");
            } catch (RuntimeException cancelError) {
                log.error("[결제] 자동 취소 실패 - 토스 개발자센터에서 수동 취소 필요 orderId={} paymentKey={}", orderId, paymentKey, cancelError);
            }
            fail(orderId, paymentKey, "주문 저장 실패로 결제 취소");
            throw new IllegalStateException("주문을 저장하지 못해 결제를 취소했어요. 다시 시도해 주세요.", e);
        }
        return new Result(orderId, order.orderName(), order.amount(), paid.method(), approvedAt);
    }

    private void fail(String orderId, String paymentKey, String reason) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            dao.markFailed(conn, orderId, paymentKey, reason);
        }
    }

    private static Result result(PaymentOrder o) {
        return new Result(o.orderId(), o.orderName(), o.amount(), o.method(), o.approvedAt());
    }
}
