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
import java.util.Map;

/**
 * 일 마감 정산 CSV: 영업일 하루 동안 상품별 판매수량/금액 + 합계
 * 형식: 집계시각,상품명,판매수량,금액  (시간대 매출 CSV와 동일, 집계시각 = yyyyMMdd HH:mm:ss.SSSS)
 * 재실행 시 덮어쓰기 (최종 확정본)
 */
public class PosDailyCloseService implements BatchTask {

    private static final Logger log = LoggerFactory.getLogger(PosDailyCloseService.class);

    private final PosSalesDao dao = new PosSalesDao();
    private final ShopDao shopDao = new ShopDao();

    @Override
    public int execute(Connection conn, BatchContext context) throws Exception {
        // 첫 줄에만 집계시각, 나머지 줄은 시각 칸을 비움
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss.SSSS"));
        String outputFileName = "daily_final_settlement_" + context.getBatchDate() + ".csv";

        // 1. 일일 집계 테이블 최종 갱신
        dao.upsertDailySummary(conn, context.getBatchDate());

        // 2. 품목별 하루 판매분
        Map salesMap = dao.selectProductSalesMap(conn, context.getBatchDate());

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outputFileName, false), StandardCharsets.UTF_8))) {

            writer.write("\uFEFF"); // Excel UTF-8 BOM
            writer.write("집계시각,상품명,판매수량,금액");
            writer.newLine();

            long totalQty = 0;
            long totalAmount = 0;
            for (Product catalog : shopDao.selectAllProducts(conn)) {
                long[] values = (long[]) salesMap.getOrDefault(catalog.itemCode(), new long[]{0, 0});
                totalQty += values[0];
                totalAmount += values[1];
                writer.write(stamp + "," + catalog.itemName() + "," + values[0] + "," + values[1]);
                writer.newLine();
                stamp = "";
            }
            writer.write(",합계," + totalQty + "," + totalAmount);
            writer.newLine();
        }

        log.info("[DailyClose] " + context.getBatchDate() + " 마감 정산 파일 발행 완료: " + outputFileName);
        return 0;
    }
}
