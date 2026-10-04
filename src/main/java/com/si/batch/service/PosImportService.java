package com.si.batch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.si.batch.common.BatchContext;
import com.si.batch.common.BatchException;
import com.si.batch.dao.PosSalesDao;
import com.si.batch.model.PosSalesDetail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class PosImportService implements BatchTask {

    private static final Logger log = LoggerFactory.getLogger(PosImportService.class);

    private final PosSalesDao dao = new PosSalesDao();

    @Override
    public int execute(Connection conn, BatchContext context) throws Exception {
        String fileName = "sales_" + context.getBatchDate() + ".csv";
        File targetFile = new File(fileName);

        // 1. 파일이 없으면 실패 처리 (쇼핑몰 실주문과 같은 테이블이라 모의 데이터를 만들어 넣지 않음)
        if (!targetFile.exists()) {
            throw new BatchException("[파일 없음] 적재할 파일이 없습니다: " + targetFile.getAbsolutePath()
                    + " (POS 파일 sales_yyyyMMdd.csv 가 실행 위치에 있는지 확인)");
        }

        // 적재 시각 = 실행(버튼 누른) 현재 시각, 이번 파일 전체 행에 같은 값 기록
        LocalDateTime importTime = PosSalesDao.toDbTime(LocalDateTime.now());
        log.info("[ImportService] 파일 적재 시작: " + fileName + " (적재 시각 " + importTime.format(PosExportService.TIME_FORMAT) + ")");

        List detailList = new ArrayList();
        int lineNumber = 0;
        int skipped = 0; // 형식 오류로 건너뛴 행 수

        try (BufferedReader reader = new BufferedReader(new FileReader(targetFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) continue;

                String[] tokens = line.split(",");
                if (tokens.length < 6) {
                    log.error("[행 오류] " + lineNumber + "행 컬럼 수 부족 (6개 필요: 영수증번호,판매일자,상품코드,상품명,단가,수량) -> " + line);
                    skipped++;
                    continue;
                }

                PosSalesDetail detail;
                try {
                    detail = new PosSalesDetail(
                            tokens[0].trim(),
                            tokens[1].trim(),
                            tokens[2].trim(),
                            tokens[3].trim(),
                            Integer.parseInt(tokens[4].trim()),
                            Integer.parseInt(tokens[5].trim()),
                            importTime
                    );
                } catch (NumberFormatException e) {
                    // 숫자 오류 1행 때문에 파일 전체가 롤백되지 않도록 해당 행만 스킵
                    log.error("[행 오류] " + lineNumber + "행 단가/수량이 숫자가 아님 -> " + line);
                    skipped++;
                    continue;
                }

                if (detail.isValid()) {
                    detailList.add(detail);
                } else {
                    log.error("[행 오류] " + lineNumber + "행 영수증번호가 비었거나 단가/수량이 0 이하 -> " + line);
                    skipped++;
                }
            }
        }

        if (detailList.isEmpty()) {
            if (skipped > 0) {
                throw new BatchException("[적재 실패] " + fileName + " 의 모든 행(" + skipped + "행)이 형식 오류입니다. 위 [행 오류] 로그를 확인하세요.");
            }
            log.warn("[경고] " + fileName + " 에 데이터가 없습니다 (빈 파일).");
            return 0;
        }

        // 2. 상세 데이터 벌크 적재
        int inserted = dao.insertBatchDetails(conn, detailList);
        log.info("[ImportService] 주문 상세 " + inserted + "건 적재 완료.");
        if (skipped > 0) {
            log.warn("[경고] 형식 오류로 " + skipped + "행을 건너뛰었습니다. 해당 행은 수정 후 다시 적재하세요.");
        }

        // 3. 일일 집계 테이블 Upsert 반영
        dao.upsertDailySummary(conn, context.getBatchDate());
        log.info("[ImportService] 일일 집계/정산 테이블 갱신 완료.");

        return 0;
    }
}