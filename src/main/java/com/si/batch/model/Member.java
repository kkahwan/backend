package com.si.batch.model;

import java.time.LocalDateTime;

// MEMBER 테이블 1행. passwordHash: 구글로만 가입하면 null / googleId: 구글 연결 전이면 null
public record Member(long memberId, String email, String passwordHash, String name, String googleId,
                     LocalDateTime createdAt) {
}
