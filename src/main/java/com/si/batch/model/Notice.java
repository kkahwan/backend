package com.si.batch.model;

import java.time.LocalDateTime;

public record Notice(int noticeId, String title, String content, boolean pinned, LocalDateTime createdAt) {
}
