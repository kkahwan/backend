package com.si.batch.service;

import com.si.batch.dao.PosSalesDao;
import com.si.batch.model.PosSalesDetail;
import com.si.batch.dao.ShopDao;
import com.si.batch.model.Product;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 장바구니 검증/가격 계산 + 결제 승인 후 POS_SALES_DETAIL 적재 (CSV 적재와 같은 테이블/DAO 사용)
 * 주문 1건의 장바구니 품목 하나하나가 영수증 1행이 됨. 결제 흐름은 PaymentService
 */
@Service
public class OrderService {

    private static final int MAX_QUANTITY = 99;

    // 주문 시점 상품/가격 (PAYMENT_ORDER_ITEM 1행)
    public record Line(String itemCode, String itemName, int unitPrice, int quantity) {
        public long amount() {
            return (long) unitPrice * quantity;
        }
    }

    private final DataSource dataSource;
    private final PosSalesDao dao = new PosSalesDao();
    private final ShopDao shopDao = new ShopDao();

    public OrderService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // RECEIPT_NO varchar(20): yyMMddHHmmss(12) + 랜덤4 = 주문번호 16자, + "-NN" = 19자. 토스 orderId(6~64자)로도 그대로 씀
    public static String newOrderNo(LocalDateTime now) {
        return now.format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    /**
     * 가격은 클라이언트 값을 믿지 않고 PRODUCT 테이블 기준으로 계산 (판매 중지 상품은 주문 불가)
     * @param cart 상품코드 -> 수량
     * @throws IllegalArgumentException 빈 장바구니 / 없는 상품 / 수량 범위
     */
    public List<Line> price(Map<String, Integer> cart) throws SQLException {
        if (cart == null || cart.isEmpty()) {
            throw new IllegalArgumentException("장바구니가 비어 있습니다.");
        }
        Map<String, Product> catalog;
        try (Connection conn = dataSource.getConnection()) {
            catalog = shopDao.selectOnSaleProducts(conn).stream()
                    .collect(Collectors.toMap(Product::itemCode, Function.identity()));
        }

        List<Line> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> item : cart.entrySet()) {
            Product product = catalog.get(item.getKey());
            if (product == null) {
                throw new IllegalArgumentException("존재하지 않는 상품입니다: " + item.getKey());
            }
            Integer qty = item.getValue();
            if (qty == null || qty < 1 || qty > MAX_QUANTITY) {
                throw new IllegalArgumentException("수량은 1~" + MAX_QUANTITY + "개만 가능합니다: " + product.itemName());
            }
            lines.add(new Line(product.itemCode(), product.itemName(), product.unitPrice(), qty));
        }
        return lines;
    }

    /**
     * 판매 행 저장. 트랜잭션(커밋/롤백)은 호출하는 쪽에서 (결제 상태 변경과 함께 묶기 위해)
     * 영업일자와 판매시각을 같은 시각에서 뽑아 자정 경계에서도 시간 파일/마감 파일 날짜가 어긋나지 않게 함
     */
    public void saveSales(Connection conn, String orderNo, List<Line> lines, LocalDateTime saleTime) throws SQLException {
        String saleDate = saleTime.format(DateTimeFormatter.BASIC_ISO_DATE);
        List<PosSalesDetail> details = new ArrayList<>();
        int lineNo = 1;
        for (Line l : lines) {
            details.add(new PosSalesDetail(String.format("%s-%02d", orderNo, lineNo++), saleDate,
                    l.itemCode(), l.itemName(), l.unitPrice(), l.quantity(), saleTime));
        }
        // INSERT IGNORE라 영수증 번호 충돌 시 조용히 누락됨 -> 건수가 다르면 실패 (호출 쪽에서 롤백)
        // 일일 집계(POS_DAILY_SUMMARY)는 주문마다 갱신하지 않음: 동시 주문 시 INSERT…SELECT 잠금 충돌(데드락) 방지
        // -> 적재/마감 배치에서 확정, 관리자 화면은 상세 테이블에서 바로 집계
        if (dao.insertBatchDetails(conn, details) != details.size()) {
            throw new IllegalStateException("주문번호 충돌이 발생했습니다.");
        }
    }
}
