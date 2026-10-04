package com.si.batch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.si.batch.common.BatchContext;
import com.si.batch.dao.PosSalesDao;
import com.si.batch.dao.ShopDao;
import com.si.batch.model.Product;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 시간대 매출 파일: 매시 정각에 직전 1시간(예: 01시 실행 -> 00:00~01:00) 판매분을
 * (관리자 버튼 실행은 누른 시각 기준 직전 1시간, 예: 10:23:45.1234 -> 09:23:45.1234~10:23:45.1234)
 * hourly_sales_날짜.txt 맨 아래에 표 형태로 이어 붙임 (메모장에서 칸이 맞게 공백 정렬)
 *
 * 20261004 01:00:00.0000
 *   상품명          판매수량        금액
 *   1번 상품               3       3,000
 *   합계                   3       3,000
 * ------------------------------------------
 */
public class PosExportService implements BatchTask {

    private static final Logger log = LoggerFactory.getLogger(PosExportService.class);

    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss.SSSS");
    private static final String SEPARATOR = "-".repeat(42);

    private final PosSalesDao dao = new PosSalesDao();
    private final ShopDao shopDao = new ShopDao();

    @Override
    public int execute(Connection conn, BatchContext context) throws Exception {
        // 정기 실행: 정각 기준(01:00:00.0000), 관리자 버튼: 누른 현재 시각 기준 직전 1시간
        LocalDateTime to = context.isManual()
                ? PosSalesDao.toDbTime(LocalDateTime.now())
                : LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        LocalDateTime from = to.minusHours(1);

        // 00시 실행분(23시대)은 전날 파일에 기록
        String outputFileName = "hourly_sales_" + from.format(DateTimeFormatter.BASIC_ISO_DATE) + ".txt";
        Map<String, long[]> salesMap = dao.selectProductSalesBetween(conn, from, to);

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outputFileName, true), StandardCharsets.UTF_8))) {

            writer.write(to.format(TIME_FORMAT));
            writer.newLine();
            writer.write(row("상품명", "판매수량", "금액"));
            writer.newLine();

            long totalQty = 0;
            long totalAmount = 0;
            for (Product catalog : shopDao.selectAllProducts(conn)) {
                long[] values = salesMap.getOrDefault(catalog.itemCode(), new long[]{0, 0});
                totalQty += values[0];
                totalAmount += values[1];
                writer.write(row(catalog.itemName(), String.format("%,d", values[0]), String.format("%,d", values[1])));
                writer.newLine();
            }
            writer.write(row("합계", String.format("%,d", totalQty), String.format("%,d", totalAmount)));
            writer.newLine();
            writer.write(SEPARATOR);
            writer.newLine();
        }

        log.info("[ExportService] " + from.format(TIME_FORMAT) + " ~ " + to.format(TIME_FORMAT) + " 매출 기록 완료 -> " + outputFileName);
        return 0;
    }

    // 상품명은 왼쪽, 숫자는 오른쪽 정렬
    static String row(String name, String qty, String amount) {
        return "  " + name + " ".repeat(Math.max(1, 16 - width(name)))
                + " ".repeat(Math.max(0, 8 - width(qty))) + qty
                + " ".repeat(Math.max(1, 12 - width(amount))) + amount;
    }

    // 고정폭 글꼴에서 한글은 영문 2칸 차지
    static int width(String s) {
        return s.codePoints().map(c -> c >= 0x1100 ? 2 : 1).sum();
    }
}
