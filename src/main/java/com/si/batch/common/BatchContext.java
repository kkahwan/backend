package com.si.batch.common;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Map;

public class BatchContext {
    private final String jobId;
    private final String functionId;
    private final String batchDate;
    private final boolean manual;

    public BatchContext(Map params) {
        this.jobId = (String) params.get("JOB_ID");
        this.functionId = (String) params.get("FUNCTION_ID");
        this.batchDate = (String) params.get("BATCH_DATE");

        // EXEC_TYPE이 MANUAL인지 검사하여 플래그 저장
        String execType = (String) params.get("EXEC_TYPE");
        this.manual = "MANUAL".equalsIgnoreCase(execType);
    }

    public boolean isValid() {
        return validate() == null;
    }

    // 무엇이 잘못됐는지 메시지로 반환 (정상이면 null)
    public String validate() {
        if (isBlank(jobId)) return "JOB_ID 파라미터가 없습니다. (예: JOB_ID=JOB_POS_01)";
        if (isBlank(functionId)) return "FUNCTION_ID 파라미터가 없습니다. (FN_IMPORT / FN_EXPORT / FN_DAILY_CLOSE 중 하나)";
        if (isBlank(batchDate)) return "BATCH_DATE 파라미터가 없습니다. (예: BATCH_DATE=20260928)";
        if (!isValidDate(batchDate)) return "BATCH_DATE가 올바른 날짜가 아닙니다: " + batchDate + " (yyyyMMdd 형식의 실제 날짜, 예: 20260928)";
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // yyyyMMdd 형식 + 실제 존재하는 날짜인지 검사 (20261332 같은 값 차단, 파일명 경로 조작 방지)
    private static boolean isValidDate(String date) {
        if (date == null) return false;
        try {
            LocalDate.parse(date, DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT));
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public String getJobId() { return jobId; }
    public String getFunctionId() { return functionId; }
    public String getBatchDate() { return batchDate; }

    // BatchController에서 호출하는 메서드
    public boolean isManual() { return manual; }

    @Override
    public String toString() {
        return "JOB_ID: " + jobId + ", FUNCTION_ID: " + functionId
                + ", BATCH_DATE: " + batchDate + ", MANUAL: " + manual;
    }
}