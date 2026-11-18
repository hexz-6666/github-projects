package com.example.fraud.store;

import com.example.fraud.domain.WorkOrderConfig;
import com.example.fraud.domain.WorkOrderConfigStatus;
import com.example.fraud.domain.WorkOrderConfigVersion;
import com.example.fraud.domain.WorkOrderTrigger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
@Profile("mysql")
public class MysqlWorkOrderConfigStore implements WorkOrderConfigStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MysqlWorkOrderConfigStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public String storageMode() { return "mysql"; }

    @Override
    public Optional<WorkOrderConfig> findConfig(long id) {
        return queryConfigs("SELECT * FROM work_order_config WHERE id=?", new Object[]{id}).stream().findFirst();
    }

    @Override
    public Optional<WorkOrderConfig> findConfigForUpdate(long id) {
        return queryConfigs("SELECT * FROM work_order_config WHERE id=? FOR UPDATE", new Object[]{id})
                .stream().findFirst();
    }

    @Override
    public List<WorkOrderConfig> findConfigs(String keyword, WorkOrderConfigStatus status,
                                             boolean publishedOnly, int offset, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM work_order_config WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<Object>();
        appendFilters(sql, args, keyword, status, publishedOnly);
        sql.append(" ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return queryConfigs(sql.toString(), args.toArray());
    }

    @Override
    public long countConfigs(String keyword, WorkOrderConfigStatus status, boolean publishedOnly) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM work_order_config WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<Object>();
        appendFilters(sql, args, keyword, status, publishedOnly);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    private void appendFilters(StringBuilder sql, java.util.List<Object> args, String keyword,
                               WorkOrderConfigStatus status, boolean publishedOnly) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            sql.append(" AND (LOWER(code) LIKE ? OR LOWER(name) LIKE ?)");
            String term = "%" + keyword.trim().toLowerCase() + "%";
            args.add(term);
            args.add(term);
        }
        if (status != null) {
            sql.append(" AND status=?");
            args.add(status.name());
        }
        if (publishedOnly) {
            sql.append(" AND published_version IS NOT NULL");
        }
    }

    @Override
    public boolean codeExists(String code) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM work_order_config WHERE code=?",
                Integer.class, code);
        return count != null && count > 0;
    }

    @Override
    public long insertConfig(WorkOrderConfig config) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO work_order_config(code,name,description,priority,trigger_json," +
                            "risk_score_threshold,sla_minutes,requires_review,assign_role,status,version," +
                            "published_version,created_by,updated_by,created_at,updated_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, config.getCode());
            statement.setString(2, config.getName());
            statement.setString(3, config.getDescription());
            statement.setInt(4, config.getPriority());
            statement.setString(5, json(config.getTrigger()));
            statement.setBigDecimal(6, config.getRiskScoreThreshold());
            statement.setInt(7, config.getSlaMinutes());
            statement.setBoolean(8, config.isRequiresReview());
            statement.setString(9, config.getAssignRole());
            statement.setString(10, config.getStatus().name());
            statement.setInt(11, config.getVersion());
            if (config.getPublishedVersion() == null) {
                statement.setObject(12, null);
            } else {
                statement.setInt(12, config.getPublishedVersion());
            }
            statement.setString(13, config.getCreatedBy());
            statement.setString(14, config.getUpdatedBy());
            statement.setTimestamp(15, Timestamp.from(config.getCreatedAt()));
            statement.setTimestamp(16, Timestamp.from(config.getUpdatedAt()));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("work order config id was not generated");
        }
        return key.longValue();
    }

    @Override
    public int updateConfig(WorkOrderConfig config, int expectedVersion) {
        return jdbc.update("UPDATE work_order_config SET name=?,description=?,priority=?,trigger_json=?," +
                        "risk_score_threshold=?,sla_minutes=?,requires_review=?,assign_role=?,status=?," +
                        "version=?,published_version=?,updated_by=?,updated_at=? WHERE id=? AND version=?",
                config.getName(), config.getDescription(), config.getPriority(), json(config.getTrigger()),
                config.getRiskScoreThreshold(), config.getSlaMinutes(), config.isRequiresReview(),
                config.getAssignRole(), config.getStatus().name(), config.getVersion(),
                config.getPublishedVersion(), config.getUpdatedBy(), Timestamp.from(config.getUpdatedAt()),
                config.getId(), expectedVersion);
    }

    @Override
    public Optional<WorkOrderConfigVersion> findVersion(long configId, int versionNo) {
        List<WorkOrderConfigVersion> rows = jdbc.query(
                "SELECT * FROM work_order_config_version WHERE config_id=? AND version_no=?",
                versionMapper(), configId, versionNo);
        return rows.stream().findFirst();
    }

    @Override
    public List<WorkOrderConfigVersion> findVersions(long configId) {
        return jdbc.query("SELECT * FROM work_order_config_version WHERE config_id=? ORDER BY version_no",
                versionMapper(), configId);
    }

    @Override
    public int nextVersionNo(long configId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM work_order_config_version WHERE config_id=?",
                Integer.class, configId);
        return value == null ? 1 : value;
    }

    @Override
    public long insertVersion(WorkOrderConfigVersion version) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO work_order_config_version(config_id,version_no,source_version,code,name," +
                            "description,priority,trigger_json,risk_score_threshold,sla_minutes," +
                            "requires_review,assign_role,published_by,published_at,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, version.getConfigId());
            statement.setInt(2, version.getVersionNo());
            statement.setInt(3, version.getSourceVersion());
            statement.setString(4, version.getCode());
            statement.setString(5, version.getName());
            statement.setString(6, version.getDescription());
            statement.setInt(7, version.getPriority());
            statement.setString(8, json(version.getTrigger()));
            statement.setBigDecimal(9, version.getRiskScoreThreshold());
            statement.setInt(10, version.getSlaMinutes());
            statement.setBoolean(11, version.isRequiresReview());
            statement.setString(12, version.getAssignRole());
            statement.setString(13, version.getPublishedBy());
            statement.setTimestamp(14, Timestamp.from(version.getPublishedAt()));
            statement.setTimestamp(15, Timestamp.from(version.getCreatedAt()));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("work order config version id was not generated");
        }
        return key.longValue();
    }

    @Override
    public List<WorkOrderConfig> findActivePublishedConfigs() {
        return queryConfigs("SELECT * FROM work_order_config WHERE status='ACTIVE' AND published_version IS NOT NULL " +
                "ORDER BY id", new Object[]{});
    }

    private List<WorkOrderConfig> queryConfigs(String sql, Object[] args) {
        return jdbc.query(sql, configMapper(), args);
    }

    private RowMapper<WorkOrderConfig> configMapper() {
        return (rs, rowNum) -> new WorkOrderConfig(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("priority"),
                trigger(rs.getString("trigger_json")),
                rs.getBigDecimal("risk_score_threshold"),
                rs.getInt("sla_minutes"),
                rs.getBoolean("requires_review"),
                rs.getString("assign_role"),
                WorkOrderConfigStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                nullableInt(rs, "published_version"),
                rs.getString("created_by"),
                rs.getString("updated_by"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private RowMapper<WorkOrderConfigVersion> versionMapper() {
        return (rs, rowNum) -> new WorkOrderConfigVersion(
                rs.getLong("id"),
                rs.getLong("config_id"),
                rs.getInt("version_no"),
                rs.getInt("source_version"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("priority"),
                trigger(rs.getString("trigger_json")),
                rs.getBigDecimal("risk_score_threshold"),
                rs.getInt("sla_minutes"),
                rs.getBoolean("requires_review"),
                rs.getString("assign_role"),
                rs.getString("published_by"),
                instant(rs, "published_at"),
                instant(rs, "created_at"));
    }

    private WorkOrderTrigger trigger(String json) {
        try {
            return objectMapper.readValue(json, WorkOrderTrigger.class);
        } catch (Exception exception) {
            throw new IllegalStateException("invalid trigger_json", exception);
        }
    }

    private String json(WorkOrderTrigger trigger) {
        try {
            return objectMapper.writeValueAsString(trigger);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("unable to serialize trigger", exception);
        }
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
