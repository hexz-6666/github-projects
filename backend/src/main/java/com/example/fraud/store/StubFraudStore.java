package com.example.fraud.store;

import com.example.fraud.api.ApiException;
import com.example.fraud.api.ReviewResponse;
import com.example.fraud.domain.AlertRecord;
import com.example.fraud.domain.AlertStatus;
import com.example.fraud.domain.FeatureRecord;
import com.example.fraud.domain.OrderRecord;
import com.example.fraud.domain.OrderStatus;
import com.example.fraud.domain.ReviewRecord;
import com.example.fraud.domain.ReviewResult;
import com.example.fraud.domain.RiskLevel;
import com.example.fraud.domain.ScoreRecord;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!mysql")
public class StubFraudStore implements FraudStore {
    private final Map<String, OrderRecord> orders = new LinkedHashMap<String, OrderRecord>();
    private final Map<String, FeatureRecord> features = new LinkedHashMap<String, FeatureRecord>();
    private final List<ScoreRecord> scores = new CopyOnWriteArrayList<ScoreRecord>();
    private final Map<Long, AlertRecord> alerts = new LinkedHashMap<Long, AlertRecord>();
    private final List<ReviewRecord> reviews = new ArrayList<ReviewRecord>();
    private final AtomicLong nextScoreId = new AtomicLong(2);
    private final AtomicLong nextReviewId = new AtomicLong(1);
    private final Object reviewLock = new Object();
    private final Clock clock;

    public StubFraudStore(Clock clock) {
        this.clock = clock;
        Instant firstOrder = Instant.parse("2026-01-03T08:00:00Z");
        Instant secondOrder = Instant.parse("2026-01-03T09:00:00Z");
        Instant thirdOrder = Instant.parse("2026-01-03T10:00:00Z");
        orders.put("DEMO-O-0001", new OrderRecord("DEMO-O-0001", "DEMO-N-0001", "DEMO-C-0001",
                "demo-****@example.invalid", OrderStatus.PAID, new BigDecimal("1200.00"),
                "CNY", "SYNTHETIC", firstOrder));
        orders.put("DEMO-O-0002", new OrderRecord("DEMO-O-0002", "DEMO-N-0002", "DEMO-C-0002",
                "demo-****@example.invalid", OrderStatus.PAID, new BigDecimal("680.00"),
                "CNY", "SYNTHETIC", secondOrder));
        orders.put("DEMO-O-0003", new OrderRecord("DEMO-O-0003", "DEMO-N-0003", "DEMO-C-0003",
                "demo-****@example.invalid", OrderStatus.CREATED, new BigDecimal("1200.00"),
                "CNY", "SYNTHETIC", thirdOrder));
        features.put("DEMO-O-0001", new FeatureRecord(1, "DEMO-O-0001", "synthetic-feature-v1",
                new BigDecimal("1200.00"), "CNY", 3, 2, 6));
        features.put("DEMO-O-0002", new FeatureRecord(2, "DEMO-O-0002", "synthetic-feature-v1",
                new BigDecimal("680.00"), "CNY", 1, 94, 1));
        features.put("DEMO-O-0003", new FeatureRecord(3, "DEMO-O-0003", "synthetic-feature-v1",
                new BigDecimal("1200.00"), "CNY", 1, 33, 1));
        ScoreRecord seedScore = new ScoreRecord(1, "DEMO-O-0001", new BigDecimal("0.9900"),
                RiskLevel.HIGH, "synthetic-rule-v1", "DEMO_RULE", new BigDecimal("0.7000"),
                Arrays.asList("HIGH_AMOUNT", "REPEATED_PAYMENT_ATTEMPTS", "NEW_ACCOUNT", "HIGH_VELOCITY"),
                Instant.parse("2026-01-03T08:04:00Z"));
        scores.add(seedScore);
        alerts.put(1L, new AlertRecord(1, "DEMO-O-0001", 1, AlertStatus.OPEN, RiskLevel.HIGH,
                seedScore, seedScore.getRuleCodes(), 1, Instant.parse("2026-01-03T08:05:00Z"),
                Instant.parse("2026-01-03T08:05:00Z")));
        // M2 第二条合成告警，供浏览器覆盖“取消”路径；不占用 nextScoreId 生成序列。
        ScoreRecord secondScore = new ScoreRecord(100, "DEMO-O-0002", new BigDecimal("0.3500"),
                RiskLevel.LOW, "synthetic-rule-v1", "DEMO_RULE", new BigDecimal("0.7000"),
                Arrays.asList("REPEATED_PAYMENT_ATTEMPTS"),
                Instant.parse("2026-01-03T09:04:00Z"));
        scores.add(secondScore);
        alerts.put(2L, new AlertRecord(2, "DEMO-O-0002", 100, AlertStatus.OPEN, RiskLevel.LOW,
                secondScore, secondScore.getRuleCodes(), 1, Instant.parse("2026-01-03T09:05:00Z"),
                Instant.parse("2026-01-03T09:05:00Z")));
    }

    @Override
    public String storageMode() { return "stub"; }

    @Override
    public void checkDatabase() {
        // An in-memory contract store is not a database and is never reported as one.
    }

    @Override
    public List<OrderRecord> findOrders(OrderStatus status, String keyword, int offset, int size) {
        String match = keyword == null ? null : keyword.toLowerCase(Locale.ROOT);
        return orders.values().stream()
                .filter(order -> status == null || order.getStatus() == status)
                .filter(order -> match == null || contains(order.getOrderId(), match)
                        || contains(order.getOrderNo(), match) || contains(order.getCustomerRef(), match))
                .sorted(Comparator.comparing(OrderRecord::getOrderedAt).reversed()
                        .thenComparing(OrderRecord::getOrderId))
                .skip(offset).limit(size).collect(Collectors.toList());
    }

    private boolean contains(String value, String lowerCaseKeyword) {
        return value.toLowerCase(Locale.ROOT).contains(lowerCaseKeyword);
    }

    @Override
    public long countOrders(OrderStatus status, String keyword) {
        return findOrders(status, keyword, 0, Integer.MAX_VALUE).size();
    }

    @Override
    public Optional<OrderRecord> findOrder(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public Optional<FeatureRecord> findFeature(String orderId) {
        return Optional.ofNullable(features.get(orderId));
    }

    @Override
    public ScoreRecord saveScore(FeatureRecord feature, ScoreRecord score) {
        ScoreRecord saved = new ScoreRecord(nextScoreId.getAndIncrement(), score.getOrderId(),
                score.getScore(), score.getRiskLevel(), score.getModelVersion(), score.getModelType(),
                score.getThreshold(), score.getRuleCodes(), score.getScoredAt());
        scores.add(saved);
        return saved;
    }

    @Override
    public List<AlertRecord> findAlerts(AlertStatus status, RiskLevel riskLevel, int offset, int size) {
        synchronized (reviewLock) {
            return alerts.values().stream()
                    .filter(alert -> status == null || alert.getAlertStatus() == status)
                    .filter(alert -> riskLevel == null || alert.getRiskLevel() == riskLevel)
                    .sorted(Comparator.comparing(AlertRecord::getCreatedAt).reversed()
                            .thenComparing(Comparator.comparing(AlertRecord::getAlertId).reversed()))
                    .skip(offset).limit(size).collect(Collectors.toList());
        }
    }

    @Override
    public long countAlerts(AlertStatus status, RiskLevel riskLevel) {
        return findAlerts(status, riskLevel, 0, Integer.MAX_VALUE).size();
    }

    @Override
    public Optional<AlertRecord> findAlert(long alertId) {
        synchronized (reviewLock) {
            return Optional.ofNullable(alerts.get(alertId));
        }
    }

    @Override
    public List<ReviewRecord> findReviews(long alertId) {
        synchronized (reviewLock) {
            return reviews.stream().filter(review -> review.getAlertId() == alertId)
                    .sorted(Comparator.comparing(ReviewRecord::getReviewId))
                    .collect(Collectors.toList());
        }
    }

    @Override
    public ReviewResponse review(long alertId, String reviewerId, ReviewResult result,
                                 String comment, int expectedVersion) {        synchronized (reviewLock) {
            AlertRecord alert = alerts.get(alertId);
            if (alert == null) {
                throw ApiException.alertNotFound();
            }
            if (alert.getAlertStatus() == AlertStatus.RESOLVED) {
                throw ApiException.alertResolved();
            }
            if (alert.getVersion() != expectedVersion) {
                throw ApiException.versionConflict();
            }
            AlertStatus previous = alert.getAlertStatus();
            AlertStatus next = result == ReviewResult.INCONCLUSIVE ? AlertStatus.REVIEWING : AlertStatus.RESOLVED;
            Instant now = clock.instant();
            ReviewRecord review = new ReviewRecord(nextReviewId.getAndIncrement(), alertId,
                    reviewerId, result, comment, previous, next, now);
            reviews.add(review);
            alerts.put(alertId, new AlertRecord(alert.getAlertId(), alert.getOrderId(), alert.getScoreId(),
                    next, alert.getRiskLevel(), alert.getScore(), alert.getReasonCodes(),
                    expectedVersion + 1, alert.getCreatedAt(), now));
            return new ReviewResponse(review.getReviewId(), alertId, previous, next,
                    expectedVersion + 1, result, now);
        }
    }

    @Override
    public void saveImportedOrder(OrderRecord order) {
        synchronized (reviewLock) {
            orders.put(order.getOrderId(), order);
        }
    }
}
