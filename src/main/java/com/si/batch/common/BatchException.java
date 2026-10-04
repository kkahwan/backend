package com.si.batch.common;

import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/**
 * 원인이 분명한 배치 실패 (메시지를 그대로 로그/관리자 화면에 표시)
 * 예) throw new BatchException("적재할 파일이 없습니다: ...");
 */
public class BatchException extends RuntimeException {

    public BatchException(String message) {
        super(message);
    }

    /**
     * 예외를 운영자가 바로 조치할 수 있는 한 줄 메시지로 변환 (원인 체인을 따라가며 아는 오류를 찾음)
     * 모르는 오류면 null -> 호출부에서 스택트레이스와 함께 기록
     */
    public static String describe(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (t instanceof BatchException) return msg;

            if (t instanceof SQLException sql) {
                String state = sql.getSQLState() == null ? "" : sql.getSQLState();
                switch (sql.getErrorCode()) {
                    case 1045: return "[DB 접속 실패] 계정 또는 비밀번호가 틀립니다. 실행 설정의 DB_PASSWORD 환경변수를 확인하세요.";
                    case 1049: return "[DB 접속 실패] 데이터베이스가 없습니다. 접속 URL의 DB 이름(batchdb)을 확인하세요.";
                    case 1146: return "[DB 스키마 오류] 테이블이 없습니다. 테이블 생성 스크립트를 실행했는지 확인하세요. (" + msg + ")";
                    case 1054: return "[DB 스키마 오류] 컬럼이 없습니다. 테이블 구조가 최신인지 확인하세요. (" + msg + ")";
                    case 1062: return "[데이터 중복] 이미 적재된 데이터(영수증번호 등)가 있어 저장하지 못했습니다. 같은 파일을 두 번 적재했는지 확인하세요. (" + msg + ")";
                    case 1366, 1406, 1292, 1264:
                        return "[데이터 형식 오류] 값의 형식이나 길이가 컬럼에 맞지 않습니다. (" + msg + ")";
                    case 1205, 1213: return "[DB 잠금] 다른 작업이 같은 데이터를 사용 중입니다. 잠시 후 다시 실행하세요.";
                    default: break;
                }
                if (state.startsWith("08")) {
                    return "[DB 연결 불가] DB 서버에 연결할 수 없습니다. MySQL이 실행 중인지, 주소/포트(localhost:3308)가 맞는지 확인하세요.";
                }
                if (state.startsWith("28")) {
                    return "[DB 접속 실패] 계정 또는 비밀번호가 틀립니다. 실행 설정의 DB_PASSWORD 환경변수를 확인하세요.";
                }
                // 하이카리 대기 시간 초과는 원인(cause)에 실제 사유가 있어 계속 탐색, 없으면 아래 마지막에서 처리
                if (!(t instanceof SQLTransientConnectionException)) {
                    return "[DB 오류] SQL 실행 중 오류가 발생했습니다. (코드 " + sql.getErrorCode() + ", " + msg + ")";
                }
            }
            if (t instanceof ConnectException) {
                return "[DB 연결 불가] DB 서버에 연결할 수 없습니다. MySQL이 실행 중인지, 주소/포트(localhost:3308)가 맞는지 확인하세요.";
            }
            if (t instanceof FileNotFoundException) {
                // 쓰기 대상 파일이 열려 있거나(엑셀 등) 폴더 권한이 없을 때
                return "[파일 쓰기 실패] 파일을 열 수 없습니다. 엑셀/메모장 등에서 파일을 열어 두었는지, 폴더 쓰기 권한이 있는지 확인하세요. (" + msg + ")";
            }
            if (t instanceof IOException) {
                return "[파일 입출력 오류] 파일을 읽거나 쓰는 중 오류가 발생했습니다. 디스크 용량과 파일 상태를 확인하세요. (" + msg + ")";
            }
            if (t instanceof MailAuthenticationException) {
                return "[메일 인증 실패] 메일 계정 로그인에 실패했습니다. MAIL_USERNAME / MAIL_PASSWORD(앱 비밀번호)를 확인하세요.";
            }
            if (t instanceof MailException) {
                return "[메일 발송 실패] 메일 서버에 연결하거나 발송하지 못했습니다. SMTP 주소/포트, 네트워크, 받는 주소(MAIL_TO)를 확인하세요. (" + msg + ")";
            }
            if (t instanceof SQLTransientConnectionException && t.getCause() == null) {
                return "[DB 연결 지연] DB 커넥션을 제때 얻지 못했습니다. DB 서버 상태와 동시 실행 작업 수를 확인하세요.";
            }
        }
        return null;
    }
}
