package com.example.fraud.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("mysql")
public class MysqlDashboardStore implements DashboardStore {
    private final JdbcTemplate jdbc;

    public MysqlDashboardStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DailyCount> orderCounts(Instant from, Instant to) {
        return jdbc.query("SELECT DATE_FORMAT(ordered_at,'%Y-%m-%d') AS d,data_origin AS c," +
                        "COUNT(*) AS n FROM dwd_order WHERE ordered_at>=? AND ordered_at<? " +
                        "GROUP BY d,c",
                new Object[]{Timestamp.from(from), Timestamp.from(to)},
                (rs, rowNum) -> new DailyCount(LocalDate.parse(rs.getString("d")),
                        rs.getString("c"), rs.getLong("n")));
    }

    @Override
    public List<DailyCount> alertCounts(Instant from, Instant to) {
        return dailyCounts("SELECT DATE_FORMAT(created_at,'%Y-%m-%d') AS d,COUNT(*) AS n " +
                        "FROM ads_risk_alert WHERE created_at>=? AND created_at<? GROUP BY d",
                from, to);
    }

    @Override
    public List<DailyCount> workOrderCreatedCounts(Instant from, Instant to) {
        return dailyCounts("SELECT DATE_FORMAT(created_at,'%Y-%m-%d') AS d,COUNT(*) AS n " +
                        "FROM work_order WHERE created_at>=? AND created_at<? GROUP BY d",
                from, to);
    }

    @Override
    public List<DailyCount> reviewResolvedCounts(Instant from, Instant to) {
        return dailyCounts("SELECT DATE_FORMAT(created_at,'%Y-%m-%d') AS d,COUNT(*) AS n " +
                        "FROM ads_alert_review WHERE new_status='RESOLVED' " +
                        "AND created_at>=? AND created_at<? GROUP BY d",
                from, to);
    }

    @Override
    public Map<String, Long> reviewResultCounts(Instant from, Instant to) {
        Map<String, Long> result = new LinkedHashMap<String, Long>();
        jdbc.query("SELECT review_result,COUNT(*) AS n FROM ads_alert_review " +
                        "WHERE created_at>=? AND created_at<? GROUP BY review_result",
                new Object[]{Timestamp.from(from), Timestamp.from(to)},
                (rs, rowNum) -> {
                    result.put(rs.getString("review_result"), rs.getLong("n"));
                    return null;
                });
        return result;
    }

    @Override
    public Map<String, Long> alertRiskSnapshot() {
        Map<String, Long> result = new LinkedHashMap<String, Long>();
        jdbc.query("SELECT risk_level,COUNT(*) AS n FROM ads_risk_alert GROUP BY risk_level",
                (rs, rowNum) -> {
                    result.put(rs.getString("risk_level"), rs.getLong("n"));
                    return null;
                });
        return result;
    }

    @Override
    public Map<String, Long> workOrderStatusSnapshot() {
        Map<String, Long> result = new LinkedHashMap<String, Long>();
        jdbc.query("SELECT status,COUNT(*) AS n FROM work_order GROUP BY status",
                (rs, rowNum) -> {
                    result.put(rs.getString("status"), rs.getLong("n"));
                    return null;
                });
        return result;
    }

    @Override
    public List<ImportBatch> terminalImports(Instant from, Instant to, int limit) {
        return jdbc.query("SELECT batch_id,source_system,status,started_at,accepted_rows,rejected_rows " +
                        "FROM etl_import_batch WHERE status IN ('SUCCEEDED','PARTIAL','FAILED') " +
                        "AND started_at>=? AND started_at<? ORDER BY started_at DESC,batch_id DESC LIMIT ?",
                new Object[]{Timestamp.from(from), Timestamp.from(to), limit},
                (rs, rowNum) -> new ImportBatch(rs.getString("batch_id"),
                        rs.getString("source_system"), rs.getString("status"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getInt("accepted_rows"), rs.getInt("rejected_rows")));
    }

    @Override
    public List<DqCheck> dqChecks(Instant from, Instant to) {
        return jdbc.query("SELECT b.source_system,d.severity,d.passed FROM dq_check_result d " +
                        "JOIN etl_import_batch b ON b.batch_id=d.batch_id " +
                        "WHERE b.status IN ('SUCCEEDED','PARTIAL','FAILED') " +
                        "AND b.started_at>=? AND b.started_at<?",
                new Object[]{Timestamp.from(from), Timestamp.from(to)},
                (rs, rowNum) -> new DqCheck(rs.getString("source_system"),
                        rs.getString("severity"), rs.getBoolean("passed")));
    }

    private List<DailyCount> dailyCounts(String sql, Instant from, Instant to) {
        return jdbc.query(sql, new Object[]{Timestamp.from(from), Timestamp.from(to)},
                (rs, rowNum) -> new DailyCount(LocalDate.parse(rs.getString("d")),
                        null, rs.getLong("n")));
    }
}
