package com.example.fraud.store;

import com.example.fraud.api.ApiException;
import com.example.fraud.api.ReviewResponse;
import com.example.fraud.domain.AlertRecord;
import com.example.fraud.domain.AlertStatus;
import com.example.fraud.domain.FeatureRecord;
import com.example.fraud.domain.OrderRecord;
import com.example.fraud.domain.OrderStatus;
import com.example.fraud.domain.ReviewResult;
import com.example.fraud.domain.RiskLevel;
import com.example.fraud.domain.ScoreRecord;
import com.example.fraud.domain.ReviewRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("mysql")
public class MysqlFraudStore implements FraudStore {
    private static final String ALERT_SELECT =
            "SELECT a.alert_id,a.order_id,a.score_id,a.alert_status,a.risk_level,a.reason_codes," +
            "a.version,a.created_at,a.updated_at,s.score,s.risk_level AS score_risk_level," +
            "s.model_version,s.model_type,s.threshold,s.rule_codes,s.scored_at " +
            "FROM ads_risk_alert a JOIN ads_risk_score s ON s.score_id=a.score_id ";
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MysqlFraudStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public String storageMode() { return "mysql"; }

    @Override
    public void checkDatabase() {
        Integer result = jdbc.queryForObject("SELECT 1", Integer.class);
        if (result == null || result != 1) {
            throw new IllegalStateException("Database check failed");
        }
    }

    private String orderWhere(OrderStatus status, String keyword, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null) {
            where.append(" AND status=?");
            args.add(status.name());
        }
        if (keyword != null) {
            where.append(" AND (LOCATE(?,LOWER(order_id))>0 OR LOCATE(?,LOWER(order_no))>0" +
                    " OR LOCATE(?,LOWER(customer_ref))>0)");
            String lowerKeyword = keyword.toLowerCase(java.util.Locale.ROOT);
            args.add(lowerKeyword);
            args.add(lowerKeyword);
            args.add(lowerKeyword);
        }
        return where.toString();
    }

    @Override
    public List<OrderRecord> findOrders(OrderStatus status, String keyword, int offset, int size) {
        List<Object> args = new ArrayList<Object>();
        String where = orderWhere(status, keyword, args);
        args.add(size);
        args.add(offset);
        return jdbc.query("SELECT order_id,order_no,customer_ref,customer_email_masked,status,amount," +
                        "currency,data_origin,ordered_at FROM dwd_order" + where +
                        " ORDER BY ordered_at DESC,order_id ASC LIMIT ? OFFSET ?",
                args.toArray(), orderMapper());
    }

    @Override
    public long countOrders(OrderStatus status, String keyword) {
        List<Object> args = new ArrayList<Object>();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM dwd_order" +
                orderWhere(status, keyword, args), args.toArray(), Long.class);
        return total == null ? 0 : total;
    }

    private RowMapper<OrderRecord> orderMapper() {
        return (rs, rowNum) -> new OrderRecord(rs.getString("order_id"), rs.getString("order_no"),
                rs.getString("customer_ref"), rs.getString("customer_email_masked"),
                OrderStatus.valueOf(rs.getString("status")), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getString("data_origin"), instant(rs, "ordered_at"));
    }

    @Override
    public Optional<OrderRecord> findOrder(String orderId) {
        List<OrderRecord> rows = jdbc.query("SELECT order_id,order_no,customer_ref," +
                "customer_email_masked,status,amount,currency,data_origin,ordered_at FROM dwd_order " +
                "WHERE order_id=?", orderMapper(), orderId);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<FeatureRecord> findFeature(String orderId) {
        List<FeatureRecord> rows = jdbc.query("SELECT feature_id,order_id,feature_version,amount," +
                "currency,payment_count,account_age_days,transaction_count_24h " +
                "FROM dws_order_risk_feature WHERE order_id=? ORDER BY feature_id DESC LIMIT 1",
                (rs, rowNum) -> new FeatureRecord(rs.getLong("feature_id"), rs.getString("order_id"),
                        rs.getString("feature_version"), rs.getBigDecimal("amount"), rs.getString("currency"),
                        rs.getInt("payment_count"), rs.getInt("account_age_days"),
                        rs.getInt("transaction_count_24h")), orderId);
        return rows.stream().findFirst();
    }

    @Override
    public ScoreRecord saveScore(FeatureRecord feature, ScoreRecord score) {
        KeyHolder keys = new GeneratedKeyHolder();
        String ruleCodes = writeCodes(score.getRuleCodes());
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ads_risk_score (order_id,feature_id,score,risk_level,model_version," +
                    "model_type,feature_version,threshold,rule_codes,scored_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, score.getOrderId());
            statement.setLong(2, feature.getFeatureId());
            statement.setBigDecimal(3, score.getScore());
            statement.setString(4, score.getRiskLevel().name());
            statement.setString(5, score.getModelVersion());
            statement.setString(6, score.getModelType());
            statement.setString(7, feature.getFeatureVersion());
            statement.setBigDecimal(8, score.getThreshold());
            statement.setString(9, ruleCodes);
            statement.setTimestamp(10, Timestamp.from(score.getScoredAt()));
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("Missing generated score key");
        }
        return new ScoreRecord(key.longValue(), score.getOrderId(), score.getScore(), score.getRiskLevel(),
                score.getModelVersion(), score.getModelType(), score.getThreshold(),
                score.getRuleCodes(), score.getScoredAt());
    }

    private String alertWhere(AlertStatus status, RiskLevel riskLevel, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null) {
            where.append(" AND a.alert_status=?");
            args.add(status.name());
        }
        if (riskLevel != null) {
            where.append(" AND a.risk_level=?");
            args.add(riskLevel.name());
        }
        return where.toString();
    }

    @Override
    public List<AlertRecord> findAlerts(AlertStatus status, RiskLevel riskLevel, int offset, int size) {
        List<Object> args = new ArrayList<Object>();
        String where = alertWhere(status, riskLevel, args);
        args.add(size);
        args.add(offset);
        return jdbc.query(ALERT_SELECT + where + " ORDER BY a.created_at DESC,a.alert_id DESC LIMIT ? OFFSET ?",
                args.toArray(), alertMapper());
    }

    @Override
    public long countAlerts(AlertStatus status, RiskLevel riskLevel) {
        List<Object> args = new ArrayList<Object>();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ads_risk_alert a" +
                alertWhere(status, riskLevel, args), args.toArray(), Long.class);
        return total == null ? 0 : total;
    }

    private RowMapper<AlertRecord> alertMapper() {
        return (rs, rowNum) -> {
            try {
                ScoreRecord score = new ScoreRecord(rs.getLong("score_id"), rs.getString("order_id"),
                        rs.getBigDecimal("score"), RiskLevel.valueOf(rs.getString("score_risk_level")),
                        rs.getString("model_version"), rs.getString("model_type"),
                        rs.getBigDecimal("threshold"), readCodes(rs.getString("rule_codes")),
                        instant(rs, "scored_at"));
                return new AlertRecord(rs.getLong("alert_id"), rs.getString("order_id"),
                        rs.getLong("score_id"), AlertStatus.valueOf(rs.getString("alert_status")),
                        RiskLevel.valueOf(rs.getString("risk_level")), score,
                        readCodes(rs.getString("reason_codes")), rs.getInt("version"),
                        instant(rs, "created_at"), instant(rs, "updated_at"));
            } catch (IOException exception) {
                throw new SQLException("Invalid stored JSON code array", exception);
            }
        };
    }

    @Override
    public Optional<AlertRecord> findAlert(long alertId) {
        return jdbc.query(ALERT_SELECT + "WHERE a.alert_id=?", alertMapper(), alertId).stream().findFirst();
    }

    @Override
    public List<ReviewRecord> findReviews(long alertId) {
        return jdbc.query("SELECT review_id,alert_id,reviewer_id,review_result,comment," +
                        "previous_status,new_status,created_at FROM ads_alert_review WHERE alert_id=? " +
                        "ORDER BY review_id ASC", (rs, rowNum) -> new ReviewRecord(rs.getLong("review_id"),
                        rs.getLong("alert_id"), rs.getString("reviewer_id"),
                        ReviewResult.valueOf(rs.getString("review_result")), rs.getString("comment"),
                        AlertStatus.valueOf(rs.getString("previous_status")),
                        AlertStatus.valueOf(rs.getString("new_status")), instant(rs, "created_at")), alertId);
    }

    @Override
    @Transactional
    public ReviewResponse review(long alertId, String reviewerId, ReviewResult result,
                                 String comment, int expectedVersion) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT alert_status,version FROM ads_risk_alert WHERE alert_id=? FOR UPDATE", alertId);
        if (rows.isEmpty()) {
            throw ApiException.alertNotFound();
        }
        Map<String, Object> row = rows.get(0);
        AlertStatus previous = AlertStatus.valueOf((String) row.get("alert_status"));
        int currentVersion = ((Number) row.get("version")).intValue();
        if (previous == AlertStatus.RESOLVED) {
            throw ApiException.alertResolved();
        }
        if (currentVersion != expectedVersion) {
            throw ApiException.versionConflict();
        }
        AlertStatus next = result == ReviewResult.INCONCLUSIVE ? AlertStatus.REVIEWING : AlertStatus.RESOLVED;
        Instant now = Instant.now();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ads_alert_review (alert_id,reviewer_id,review_result,comment," +
                    "previous_status,new_status,created_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, alertId);
            statement.setString(2, reviewerId);
            statement.setString(3, result.name());
            statement.setString(4, comment);
            statement.setString(5, previous.name());
            statement.setString(6, next.name());
            statement.setTimestamp(7, Timestamp.from(now));
            return statement;
        }, keys);
        int updated = jdbc.update("UPDATE ads_risk_alert SET alert_status=?,version=version+1,updated_at=? " +
                "WHERE alert_id=? AND version=?", next.name(), Timestamp.from(now), alertId, expectedVersion);
        if (updated != 1) {
            throw ApiException.versionConflict();
        }
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("Missing generated review key");
        }
        return new ReviewResponse(key.longValue(), alertId, previous, next,
                expectedVersion + 1, result, now);
    }

    private List<String> readCodes(String json) throws IOException {
        return objectMapper.readValue(json, new TypeReference<List<String>>() { });
    }

    private String writeCodes(List<String> codes) {
        try {
            return objectMapper.writeValueAsString(codes);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize code array", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (value == null) {
            throw new SQLException("Missing UTC timestamp");
        }
        return LocalDateTime.parse(value.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toInstant(ZoneOffset.UTC);
    }
}
