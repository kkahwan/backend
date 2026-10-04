package com.si.batch.service;

import com.si.batch.dao.PosSalesDao;
import com.si.batch.model.PosSalesDetail;
import com.si.batch.dao.ShopDao;
import com.si.batch.model.Product;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 쇼핑몰 주문 -> POS_SALES_DETAIL 적재 (CSV 적재와 같은 테이블/DAO 사용)
 * 주문 1건의 장바구니 품목 하나하나가 영수증 1행이 됨
 */
@Service
public class OrderService {

    private static final int MAX_QUANTITY = 99;

    private final DataSource dataSource;
    private final PosSalesDao dao = new PosSalesDao();
    private final ShopDao shopDao = new ShopDao();

    public OrderService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * @param cart 상품코드 -> 수량
     * @return 주문번호, 결제금액
     */
    public Map<String, Object> placeOrder(Map<String, Integer> cart) throws Exception {
        if (cart == null || cart.isEmpty()) {
            throw new IllegalArgumentException("장바구니가 비어 있습니다.");
        }

        // 영업일자와 판매시각을 같은 시각에서 뽑아 자정 경계에서도 시간 파일/마감 파일 날짜가 어긋나지 않게 함
        // (23:59:59.9999까지 전날, 00:00:00부터 다음날)
        LocalDateTime now = LocalDateTime.now();
        String saleDate = now.format(DateTimeFormatter.BASIC_ISO_DATE);
        // RECEIPT_NO varchar(20): yyMMddHHmmss(12) + 랜덤4 = 주문번호 16자, + "-NN" = 19자
        String orderNo = now.format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));

        // 가격은 클라이언트 값을 믿지 않고 PRODUCT 테이블 기준으로 계산 (판매 중지 상품은 주문 불가)
        Map<String, Product> catalog;
        try (Connection conn = dataSource.getConnection()) {
            catalog = shopDao.selectOnSaleProducts(conn).stream()
                    .collect(Collectors.toMap(Product::itemCode, Function.identity()));
        }

        List<PosSalesDetail> lines = new ArrayList<>();
        long totalAmount = 0;
        int lineNo = 1;
        for (Map.Entry<String, Integer> item : cart.entrySet()) {
            Product product = catalog.get(item.getKey());
            if (product == null) {
                throw new IllegalArgumentException("존재하지 않는 상품입니다: " + item.getKey());
            }
            Integer qty = item.getValue();
            if (qty == null || qty < 1 || qty > MAX_QUANTITY) {
                throw new IllegalArgumentException("수량은 1~" + MAX_QUANTITY + "개만 가능합니다: " + product.itemName());
            }

            PosSalesDetail line = new PosSalesDetail(String.format("%s-%02d", orderNo, lineNo++), saleDate,
                    product.itemCode(), product.itemName(), product.unitPrice(), qty, now);
            lines.add(line);
            totalAmount += line.calculateTotalPrice();
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // INSERT IGNORE라 주문번호 충돌 시 조용히 누락됨 -> 건수가 다르면 롤백
                if (dao.insertBatchDetails(conn, lines) != lines.size()) {
                    throw new IllegalStateException("주문번호 충돌이 발생했습니다. 다시 시도해 주세요.");
                }
                // 일일 집계(POS_DAILY_SUMMARY)는 주문마다 갱신하지 않음: 동시 주문 시 INSERT…SELECT 잠금 충돌(데드락) 방지
                // -> 적재/마감 배치에서 확정, 관리자 화면은 상세 테이블에서 바로 집계
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }

        return Map.of("orderNo", orderNo, "totalAmount", totalAmount);
    }
}
