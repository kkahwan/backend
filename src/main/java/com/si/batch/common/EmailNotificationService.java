package com.si.batch.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.si.batch.common.mail.MailMessageTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final String password;
    private final String receiver;

    public EmailNotificationService(JavaMailSender mailSender,
                                    @Value("${spring.mail.username}") String from,
                                    @Value("${spring.mail.password}") String password,
                                    @Value("${app.mail.admin-to}") String receiver) {
        this.mailSender = mailSender;
        this.from = from;
        this.password = password;
        this.receiver = receiver;
    }

    public void sendManualTriggerAlert(String jobId, String functionId, String batchDate, String executionTime) {
        // 1. 전용 템플릿 클래스에서 제목과 본문을 조립해옴
        String subject = MailMessageTemplate.buildSubject(functionId);
        String body = MailMessageTemplate.buildManualAlertBody(jobId, functionId, batchDate, executionTime);

        // 2. 계정 미설정 시 시뮬레이션 (환경변수 MAIL_USERNAME / MAIL_PASSWORD / MAIL_TO)
        if (from.isBlank() || password.isBlank() || receiver.isBlank()) {
            log.info("[이메일 발송 스킵] SMTP 계정이 등록되지 않아 시뮬레이션으로 대체합니다.");
            log.info("제목: " + subject + "\n본문:\n" + body);
            return;
        }

        // 3. SMTP 메일 전송
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(receiver);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
        log.info("[Email] 감사 알림 메일 전송 완료 -> " + receiver);
    }
}
