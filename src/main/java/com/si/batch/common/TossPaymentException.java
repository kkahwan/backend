package com.si.batch.common;

// 토스페이먼츠가 거절한 요청. code/message는 토스 응답 그대로 (예: REJECT_CARD_PAYMENT, 한도초과 안내 문구)
public class TossPaymentException extends RuntimeException {

    private final String code;

    public TossPaymentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
