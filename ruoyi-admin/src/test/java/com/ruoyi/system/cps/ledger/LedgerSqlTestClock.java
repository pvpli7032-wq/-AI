package com.ruoyi.system.cps.ledger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Per-connection test clock alignment only; never changes server/global configuration. */
final class LedgerSqlTestClock {
    private LedgerSqlTestClock() {}
    static Instant now(JdbcTemplate jdbc) {
        // Persisted lifecycle columns are millisecond precision. Windows' JVM/SQL clocks
        // can differ within one scheduler tick; let that tick elapse without backdating.
        Instant paid=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",java.sql.Timestamp.class).toInstant();
        jdbc.queryForObject("SELECT SLEEP(0.025)",Integer.class);
        return paid;
    }
    static JdbcTemplate jdbc(DriverManagerDataSource migrated) {
        String url=migrated.getUrl();
        int question=url.indexOf('?');
        String base=question<0 ? url : url.substring(0,question);
        String options=question<0 ? "" : Arrays.stream(url.substring(question+1).split("&"))
            .filter(o -> !o.matches("(?i)(serverTimezone|connectionTimeZone|forceConnectionTimeZoneToSession)=.*"))
            .filter(o -> !o.isEmpty()).collect(Collectors.joining("&"));
        String offset=Instant.now().atZone(ZoneId.systemDefault()).getOffset().getId();
        if (offset.equals("Z")) offset="+00:00";
        String aligned=base+"?"+(options.isEmpty() ? "" : options+"&")
            +"connectionTimeZone="+URLEncoder.encode(offset,StandardCharsets.UTF_8)
            +"&forceConnectionTimeZoneToSession=true";
        return new JdbcTemplate(new DriverManagerDataSource(aligned,migrated.getUsername(),migrated.getPassword()));
    }
}
