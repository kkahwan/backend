package com.si.batch.common;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * MySQL 드라이버 SQL 로그(profileSQL) 중 실제 실행 SQL만 남김 (logback-spring.xml 에서 사용)
 * 버림: 결과 수신(FETCH) 같은 부가 이벤트, 커넥션 설정용 SET 문
 */
public class SqlLogFilter extends Filter<ILoggingEvent> {

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (!"MySQL".equals(event.getLoggerName())) return FilterReply.NEUTRAL;
        String msg = event.getFormattedMessage();
        return msg.startsWith("[QUERY]") && !msg.startsWith("[QUERY] SET ") ? FilterReply.NEUTRAL : FilterReply.DENY;
    }
}
