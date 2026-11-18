package com.example.fraud.store;

import com.example.fraud.domain.OpsLogCode;
import com.example.fraud.domain.OpsLogEvent;
import com.example.fraud.domain.OpsLogLevel;
import com.example.fraud.domain.OpsLogSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("mysql")
public class MysqlOpsLogStore implements OpsLogStore {
    private final JdbcTemplate jdbc;

    public MysqlOpsLogStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(OpsLogEvent event) {
        jdbc.update("INSERT INTO ops_log_event(occurred_at,source,level,route,code,summary," +
                        "task_type,task_id,request_id,related_request_id,actor_id,build_version," +
                        "duration_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Timestamp.from(event.getOccurredAt()), event.getSource().name(),
                event.getLevel().name(), event.getRoute(), event.getCode().name(),
                event.getSummary(), event.getTaskType(), event.getTaskId(),
                event.getRequestId(), event.getRelatedRequestId(), event.getActorId(),
                event.getBuildVersion(), event.getDurationMs());
    }

    @Override
    public List<OpsLogEvent> query(Query query) {
        StringBuilder sql = new StringBuilder("SELECT event_id,occurred_at,source,level,route," +
                "code,task_type,task_id,request_id,related_request_id,actor_id,build_version," +
                "duration_ms FROM ops_log_event WHERE 1=1");
        List<Object> args = new ArrayList<Object>();
        if (query.getSource() != null) {
            sql.append(" AND source=?");
            args.add(query.getSource());
        }
        if (query.getMinLevel() != null) {
            List<OpsLogLevel> levels = OpsLogLevel.atLeastValues(
                    OpsLogLevel.valueOf(query.getMinLevel()));
            sql.append(" AND level IN (");
            for (int i = 0; i < levels.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append('?');
                args.add(levels.get(i).name());
            }
            sql.append(')');
        }
        if (query.getTaskType() != null) {
            sql.append(" AND task_type=?");
            args.add(query.getTaskType());
        }
        if (query.getTaskId() != null) {
            sql.append(" AND task_id=?");
            args.add(query.getTaskId());
        }
        if (query.getRequestId() != null) {
            sql.append(" AND request_id=?");
            args.add(query.getRequestId());
        }
        if (query.getRelatedRequestId() != null) {
            sql.append(" AND related_request_id=?");
            args.add(query.getRelatedRequestId());
        }
        if (query.getFrom() != null) {
            sql.append(" AND occurred_at>=?");
            args.add(Timestamp.from(query.getFrom()));
        }
        if (query.getTo() != null) {
            sql.append(" AND occurred_at<?");
            args.add(Timestamp.from(query.getTo()));
        }
        if (query.getBeforeOccurredAt() != null) {
            sql.append(" AND (occurred_at<? OR (occurred_at=? AND event_id<?))");
            args.add(Timestamp.from(query.getBeforeOccurredAt()));
            args.add(Timestamp.from(query.getBeforeOccurredAt()));
            args.add(query.getBeforeEventId());
        }
        sql.append(" ORDER BY occurred_at DESC,event_id DESC LIMIT ?");
        args.add(query.getLimit());
        return jdbc.query(sql.toString(), mapper(), args.toArray());
    }

    @Override
    public long count() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM ops_log_event", Long.class);
        return value == null ? 0L : value;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteOlderThan(Instant cutoff, int limit) {
        return jdbc.update("DELETE FROM ops_log_event WHERE occurred_at < ? LIMIT ?",
                Timestamp.from(cutoff), limit);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteOldest(int limit) {
        return jdbc.update("DELETE FROM ops_log_event ORDER BY occurred_at,event_id LIMIT ?", limit);
    }

    @Override
    public boolean probe() {
        jdbc.queryForList("SELECT 1 FROM ops_log_event LIMIT 1");
        return true;
    }

    private RowMapper<OpsLogEvent> mapper() {
        return (rs, rowNum) -> new OpsLogEvent(
                rs.getLong("event_id"),
                rs.getTimestamp("occurred_at").toInstant(),
                OpsLogSource.valueOf(rs.getString("source")),
                OpsLogLevel.valueOf(rs.getString("level")),
                rs.getString("route"),
                OpsLogCode.valueOf(rs.getString("code")),
                rs.getString("task_type"),
                rs.getString("task_id"),
                rs.getString("request_id"),
                rs.getString("related_request_id"),
                rs.getString("actor_id"),
                rs.getString("build_version"),
                rs.getObject("duration_ms") == null ? null : rs.getInt("duration_ms"));
    }
}
