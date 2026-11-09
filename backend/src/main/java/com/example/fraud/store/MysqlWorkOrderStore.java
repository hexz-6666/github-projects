package com.example.fraud.store;

import com.example.fraud.domain.WorkOrderEventRecord;
import com.example.fraud.domain.WorkOrderEventType;
import com.example.fraud.domain.WorkOrderRecord;
import com.example.fraud.domain.WorkOrderScope;
import com.example.fraud.domain.WorkOrderStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
@Profile("mysql")
public class MysqlWorkOrderStore implements WorkOrderStore {
    private final JdbcTemplate jdbc;

    public MysqlWorkOrderStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String storageMode() { return "mysql"; }

    @Override
    public long insert(WorkOrderRecord workOrder) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO work_order(alert_id,config_id,config_version_id,config_version_no," +
                                "config_snapshot_json,assign_role,assignee_id,status,requires_review,sla_minutes," +
                                "due_at,resolution_review_id,resolved_at,cancelled_at,version,created_by,updated_by," +
                                "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, workOrder.getAlertId());
                statement.setLong(2, workOrder.getConfigId());
                statement.setLong(3, workOrder.getConfigVersionId());
                statement.setInt(4, workOrder.getConfigVersionNo());
                statement.setString(5, workOrder.getConfigSnapshotJson());
                statement.setString(6, workOrder.getAssignRole());
                statement.setString(7, workOrder.getAssigneeId());
                statement.setString(8, workOrder.getStatus().name());
                statement.setBoolean(9, workOrder.isRequiresReview());
                statement.setInt(10, workOrder.getSlaMinutes());
                statement.setTimestamp(11, Timestamp.from(workOrder.getDueAt()));
                if (workOrder.getResolutionReviewId() == null) {
                    statement.setObject(12, null);
                } else {
                    statement.setLong(12, workOrder.getResolutionReviewId());
                }
                setNullableInstant(statement, 13, workOrder.getResolvedAt());
                setNullableInstant(statement, 14, workOrder.getCancelledAt());
                statement.setInt(15, workOrder.getVersion());
                statement.setString(16, workOrder.getCreatedBy());
                statement.setString(17, workOrder.getUpdatedBy());
                statement.setTimestamp(18, Timestamp.from(workOrder.getCreatedAt()));
                statement.setTimestamp(19, Timestamp.from(workOrder.getUpdatedAt()));
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException exception) {
            throw new DuplicateWorkOrderException();
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("work order id was not generated");
        }
        return key.longValue();
    }

    @Override
    public Optional<WorkOrderRecord> findByAlertId(long alertId) {
        return jdbc.query("SELECT * FROM work_order WHERE alert_id=?", workOrderMapper(), alertId)
                .stream().findFirst();
    }

    @Override
    public Optional<WorkOrderRecord> findById(long id) {
        return jdbc.query("SELECT * FROM work_order WHERE id=?", workOrderMapper(), id)
                .stream().findFirst();
    }

    @Override
    public Optional<WorkOrderRecord> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM work_order WHERE id=? FOR UPDATE", workOrderMapper(), id)
                .stream().findFirst();
    }

    @Override
    public List<WorkOrderRecord> findWorkOrders(WorkOrderScope scope, String username, String role,
                                                WorkOrderStatus status, int offset, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM work_order WHERE 1=1");
        List<Object> args = new ArrayList<Object>();
        appendScope(sql, args, scope, username, role);
        if (status != null) {
            sql.append(" AND status=?");
            args.add(status.name());
        }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbc.query(sql.toString(), workOrderMapper(), args.toArray());
    }

    @Override
    public long countWorkOrders(WorkOrderScope scope, String username, String role, WorkOrderStatus status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM work_order WHERE 1=1");
        List<Object> args = new ArrayList<Object>();
        appendScope(sql, args, scope, username, role);
        if (status != null) {
            sql.append(" AND status=?");
            args.add(status.name());
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    private void appendScope(StringBuilder sql, List<Object> args, WorkOrderScope scope,
                             String username, String role) {
        if (scope == WorkOrderScope.MINE) {
            sql.append(" AND assignee_id=?");
            args.add(username);
        } else if (scope == WorkOrderScope.QUEUE) {
            sql.append(" AND assignee_id IS NULL AND status='OPEN'");
            if ("REVIEWER".equals(role)) {
                sql.append(" AND assign_role='REVIEWER'");
            }
        }
    }

    @Override
    public int update(WorkOrderRecord workOrder, int expectedVersion, WorkOrderStatus expectedStatus) {
        return jdbc.update("UPDATE work_order SET assignee_id=?,status=?,resolution_review_id=?," +
                        "resolved_at=?,cancelled_at=?,version=?,updated_by=?,updated_at=? " +
                        "WHERE id=? AND version=? AND status=?",
                workOrder.getAssigneeId(), workOrder.getStatus().name(),
                workOrder.getResolutionReviewId(),
                workOrder.getResolvedAt() == null ? null : Timestamp.from(workOrder.getResolvedAt()),
                workOrder.getCancelledAt() == null ? null : Timestamp.from(workOrder.getCancelledAt()),
                workOrder.getVersion(), workOrder.getUpdatedBy(),
                Timestamp.from(workOrder.getUpdatedAt()), workOrder.getId(), expectedVersion,
                expectedStatus.name());
    }

    @Override
    public long insertEvent(WorkOrderEventRecord event) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO work_order_event(work_order_id,event_type,from_status,to_status," +
                            "actor_id,actor_role,note,request_id,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, event.getWorkOrderId());
            statement.setString(2, event.getEventType().name());
            statement.setString(3, event.getFromStatus() == null ? null : event.getFromStatus().name());
            statement.setString(4, event.getToStatus() == null ? null : event.getToStatus().name());
            statement.setString(5, event.getActorId());
            statement.setString(6, event.getActorRole());
            statement.setString(7, event.getNote());
            statement.setString(8, event.getRequestId());
            statement.setTimestamp(9, Timestamp.from(event.getCreatedAt()));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("work order event id was not generated");
        }
        return key.longValue();
    }

    @Override
    public List<WorkOrderEventRecord> findEvents(long workOrderId) {
        return jdbc.query("SELECT * FROM work_order_event WHERE work_order_id=? " +
                        "ORDER BY created_at,event_id", eventMapper(), workOrderId);
    }

    private RowMapper<WorkOrderRecord> workOrderMapper() {
        return (rs, rowNum) -> new WorkOrderRecord(
                rs.getLong("id"),
                rs.getLong("alert_id"),
                rs.getLong("config_id"),
                rs.getLong("config_version_id"),
                rs.getInt("config_version_no"),
                rs.getString("config_snapshot_json"),
                rs.getString("assign_role"),
                rs.getString("assignee_id"),
                WorkOrderStatus.valueOf(rs.getString("status")),
                rs.getBoolean("requires_review"),
                rs.getInt("sla_minutes"),
                instant(rs, "due_at"),
                nullableLong(rs, "resolution_review_id"),
                instant(rs, "resolved_at"),
                instant(rs, "cancelled_at"),
                rs.getInt("version"),
                rs.getString("created_by"),
                rs.getString("updated_by"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private RowMapper<WorkOrderEventRecord> eventMapper() {
        return (rs, rowNum) -> new WorkOrderEventRecord(
                rs.getLong("event_id"),
                rs.getLong("work_order_id"),
                WorkOrderEventType.valueOf(rs.getString("event_type")),
                nullableStatus(rs.getString("from_status")),
                nullableStatus(rs.getString("to_status")),
                rs.getString("actor_id"),
                rs.getString("actor_role"),
                rs.getString("note"),
                rs.getString("request_id"),
                instant(rs, "created_at"));
    }

    private WorkOrderStatus nullableStatus(String value) {
        return value == null ? null : WorkOrderStatus.valueOf(value);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void setNullableInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }
}
