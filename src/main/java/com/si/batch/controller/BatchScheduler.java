package com.si.batch.controller;

import com.si.batch.common.BatchContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 정기 배치 스케줄 (EXEC_TYPE=SCHEDULED -> 관리자 수동 실행 알림 메일 미발송)
 * - 매시 정각: 직전 1시간 매출을 hourly_sales_날짜.txt 맨 아래에 추가
 * - 매일 00:10: 전날 일 마감 정산 CSV (daily_final_settlement_날짜.csv)
 */
@Component
public class BatchScheduler {

    private static final String ZONE = "Asia/Seoul";

    private final BatchController batchController;

    public BatchScheduler(BatchController batchController) {
        this.batchController = batchController;
    }

    @Scheduled(cron = "0 0 * * * *", zone = ZONE)
    public void hourlyExport() {
        // 00:00 실행분은 방금 끝난 23시대 = 전날 영업일로 기록
        run("FN_EXPORT", LocalDateTime.now().minusMinutes(1).toLocalDate());
    }

    @Scheduled(cron = "0 10 0 * * *", zone = ZONE)
    public void dailyClose() {
        run("FN_DAILY_CLOSE", LocalDate.now().minusDays(1));
    }

    private void run(String functionId, LocalDate date) {
        batchController.run(new BatchContext(Map.of(
                "JOB_ID", "JOB_POS_01",
                "FUNCTION_ID", functionId,
                "BATCH_DATE", date.format(DateTimeFormatter.BASIC_ISO_DATE),
                "EXEC_TYPE", "SCHEDULED")));
    }
}
