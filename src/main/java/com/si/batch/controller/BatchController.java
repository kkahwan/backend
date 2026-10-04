package com.si.batch.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.si.batch.common.BatchContext;
import com.si.batch.common.BatchException;
import com.si.batch.common.EmailNotificationService;
import com.si.batch.service.BatchTask;
import com.si.batch.service.PosDailyCloseService;
import com.si.batch.service.PosExportService;
import com.si.batch.service.PosImportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class BatchController {

    private static final Logger log = LoggerFactory.getLogger(BatchController.class);

    private final DataSource dataSource;
    private final EmailNotificationService notificationService;
    private final String dbPassword;

    public BatchController(DataSource dataSource, EmailNotificationService notificationService,
                           @Value("${DB_PASSWORD:}") String dbPassword) {
        this.dbPassword = dbPassword;
        this.dataSource = dataSource;
        this.notificationService = notificationService;
    }

    // 실행 결과: code 0 성공 / 1 실패, message는 로그와 관리자 화면에 그대로 표시
    public record Result(int code, String message) {}

    // 스케줄 실행과 관리자 수동 실행이 겹쳐 같은 CSV에 동시에 쓰지 않도록 한 번에 하나씩 실행
    // ponytail: 전역 잠금, 배치 종류가 늘어 대기가 길어지면 FUNCTION_ID별 잠금으로 분리
    public synchronized Result run(BatchContext context) {
        log.info("========== 배치 프로그램 시작 ==========");

        String invalid = context.validate();
        if (invalid != null) {
            return fail("[파라미터 오류] " + invalid);
        }
        // 없으면 DB에 "${DB_PASSWORD}" 글자 그대로가 비밀번호로 전달돼 "비밀번호 틀림"으로 보이므로 미리 차단
        if (dbPassword.isEmpty()) {
            return fail("[설정 오류] DB_PASSWORD 환경변수가 없습니다. 실행 설정(Edit Configurations) > Environment variables 에 DB_PASSWORD=비밀번호 를 추가하세요.");
        }
        log.info("[파라미터 정상 확인] JOB_ID: " + context.getJobId()
                + ", FUNCTION_ID: " + context.getFunctionId()
                + ", BATCH_DATE: " + context.getBatchDate());

        BatchTask task;
        switch (context.getFunctionId()) {
            case "FN_IMPORT":
                task = new PosImportService();
                break;
            case "FN_EXPORT":
                task = new PosExportService();
                break;
            case "FN_DAILY_CLOSE":
                task = new PosDailyCloseService();
                break;
            default:
                return fail("[파라미터 오류] 지원하지 않는 FUNCTION_ID 입니다: " + context.getFunctionId()
                        + " (FN_IMPORT / FN_EXPORT / FN_DAILY_CLOSE 중 하나)");
        }

        // [중요] 관리자 수동 실행인 경우 메일 알림 발송 (시뮬레이션 로그 or 실제 발송)
        // 메일은 부가 기능이므로 실패해도 배치는 계속 진행
        if (context.isManual()) {
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSS"));
            try {
                notificationService.sendManualTriggerAlert(
                        context.getJobId(), context.getFunctionId(), context.getBatchDate(), now
                );
            } catch (RuntimeException e) {
                String reason = BatchException.describe(e);
                log.warn("[경고] 수동 실행 알림 메일을 보내지 못했습니다 (배치는 계속 진행). "
                        + (reason != null ? reason : e.getMessage()));
            }
        }

        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            int result = task.execute(conn, context);
            if (result != 0) {
                throw new BatchException("[작업 실패] " + context.getFunctionId() + " 작업이 실패 코드 " + result + "를 반환했습니다.");
            }

            conn.commit();
            log.info("[트랜잭션 커밋 완료]");
            log.info("========== 배치 프로그램 종료 (코드: 0) ==========");
            return new Result(0, context.getFunctionId() + " 성공");

        } catch (Exception e) {
            String reason = BatchException.describe(e);
            if (reason != null) {
                if (reason.contains("DB_PASSWORD")) reason += " " + passwordHint(dbPassword);
                log.error(reason);
            } else {
                // 예상하지 못한 오류는 원인 추적용 스택트레이스까지 기록
                reason = "[알 수 없는 오류] " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + " (logs/app.log 의 상세 내용 확인)";
                log.error(reason, e);
            }

            if (conn != null) {
                try {
                    conn.rollback();
                    log.error("[트랜잭션 롤백 완료] 이번 실행에서 변경한 DB 내용은 반영되지 않았습니다.");
                } catch (SQLException ex) {
                    log.error("[롤백 실패] 롤백 중 오류가 발생했습니다. DB 데이터를 직접 확인하세요: " + ex.getMessage());
                }
            }
            log.info("========== 배치 프로그램 종료 (코드: 1) ==========");
            return new Result(1, reason);

        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException e) {
                    log.warn("[경고] DB 연결 해제 중 오류 (결과에는 영향 없음): " + e.getMessage());
                }
            }
        }
    }

    // 비밀번호는 노출하지 않고, 흔한 입력 실수(공백/따옴표/한글 입력 상태)만 알려줌
    static String passwordHint(String pw) {
        if (pw == null || pw.isEmpty()) return "(DB_PASSWORD가 비어 있습니다)";
        String hint = "(입력된 값: " + pw.length() + "자";
        if (!pw.equals(pw.strip())) hint += ", 앞뒤에 공백 있음";
        if (pw.startsWith("\"") || pw.startsWith("'")) hint += ", 따옴표 포함됨 - 따옴표 없이 입력";
        if (!pw.chars().allMatch(c -> c < 128)) hint += ", 한글 등 영문이 아닌 글자 포함 - 한/영 키 확인";
        return hint + ")";
    }

    private Result fail(String message) {
        log.error(message);
        log.info("========== 배치 프로그램 종료 (코드: 1) ==========");
        return new Result(1, message);
    }
}
