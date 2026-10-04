package com.si.batch.common.mail;

public class MailMessageTemplate {

    /**
     * 메일 제목 생성
     */
    public static String buildSubject(String functionId) {
        return "[관리자 긴급 실행 감지] " + functionId + " 수동 실행 안내";
    }

    /**
     * 메일 본문 생성 (문구 수정 시 이 메서드 안의 글자만 고치면 됨)
     */
    public static String buildManualAlertBody(String jobId, String functionId, String batchDate, String executionTime) {
        StringBuilder sb = new StringBuilder();

        sb.append("==================================================\n");
        sb.append("   [홈쇼핑 정산 시스템 관리자 수동 실행 알림]\n");
        sb.append("==================================================\n\n");
        sb.append("정기 스케줄러가 아닌 관리자 수동 조작으로 배치가 즉시 실행되었습니다.\n\n");
        sb.append("[실행 상세 내역]\n");
        sb.append("--------------------------------------------------\n");
        sb.append("▶ 실행 시각          : ").append(executionTime).append("\n");
        sb.append("▶ 실행 작업 (JOB_ID)  : ").append(jobId).append("\n");
        sb.append("▶ 기능 코드 (FN_ID)   : ").append(functionId).append("\n");
        sb.append("▶ 대상 영업 일자      : ").append(batchDate).append("\n");
        sb.append("--------------------------------------------------\n\n");
        sb.append("※ 본 메일은 시스템 보안 감사(Audit) 목적으로 자동 발송되었습니다.\n");
        sb.append("※ 문의: 시스템 운영팀 (내선 1234)\n");

        return sb.toString();
    }
}