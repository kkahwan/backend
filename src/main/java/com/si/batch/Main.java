package com.si.batch;

import com.si.batch.common.BatchContext;
import com.si.batch.controller.BatchController;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 배치 서버 실행 (종료하기 전까지 계속 실행)
 * - 웹서버(쇼핑몰 + 관리자 화면 http://localhost:8080) + 정기 스케줄(매시 정각 시간대 매출, 매일 00:10 마감 정산)
 * - 실행 인자로 배치를 주면 서버 시작 직후 그 배치를 한 번 실행
 *   예) JOB_ID=JOB_POS_01 FUNCTION_ID=FN_EXPORT BATCH_DATE=20260928
 * - RUN_ONCE=Y 를 함께 주면 예전처럼 배치 1회 실행 후 종료 (외부 스케줄러용, 웹서버 없음)
 */
public class Main {

    public static void main(String[] args) {
        Map<String, String> params = new HashMap<>();
        for (String arg : args) {
            String[] kv = arg.split("=", 2);
            if (kv.length == 2) params.put(kv[0].trim().toUpperCase(), kv[1].trim());
        }
        params.putIfAbsent("EXEC_TYPE", "BATCH"); // 수동 실행 알림 메일 미발송

        if ("Y".equalsIgnoreCase(params.get("RUN_ONCE"))) {
            int code;
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ShopApplication.class)
                    .web(WebApplicationType.NONE)
                    // 웹서버를 띄우지 않아 관리자 로그인은 쓰이지 않음 -> 환경변수가 없어도 시작되도록 임의값 (환경변수가 있으면 그 값 우선)
                    .properties("ADMIN_PASSWORD=" + UUID.randomUUID())
                    .run()) {
                code = ctx.getBean(BatchController.class).run(new BatchContext(params)).code();
            }
            System.exit(code);
        }

        SpringApplicationBuilder server = new SpringApplicationBuilder(ShopApplication.class);
        String adminPassword = System.getenv("ADMIN_PASSWORD");
        if (adminPassword == null || adminPassword.isBlank()) {
            // 관리자 비밀번호 미설정 시 임시 비밀번호로 시작 (로그 파일에 남지 않도록 콘솔에만 출력)
            adminPassword = UUID.randomUUID().toString().substring(0, 8);
            server.properties("ADMIN_PASSWORD=" + adminPassword);
            System.out.println("[안내] ADMIN_PASSWORD 환경변수가 없어 임시 관리자 비밀번호로 시작합니다: admin / " + adminPassword
                    + " (재시작하면 바뀜, 고정하려면 setx ADMIN_PASSWORD 비밀번호 후 IntelliJ 재시작)");
        }

        ConfigurableApplicationContext ctx = server.run();
        if (params.containsKey("FUNCTION_ID")) {
            ctx.getBean(BatchController.class).run(new BatchContext(params));
        }
        System.out.println("[안내] 배치 서버 실행 중 - 관리자 화면 http://localhost:8080 , 종료는 IntelliJ의 ■ (Stop) 버튼");
    }
}
