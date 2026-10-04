package com.si.batch.model;

import java.time.LocalDateTime;

// endAt == null 이면 계속 노출
public record Banner(int bannerId, String title, String subtitle, String linkUrl, String linkText,
                     int sortOrder, LocalDateTime startAt, LocalDateTime endAt) {
}
