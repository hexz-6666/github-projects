package com.example.fraud.store;

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
import java.util.List;
import java.util.Optional;

public interface FraudStore {
    String storageMode();
    void checkDatabase();
    List<OrderRecord> findOrders(OrderStatus status, String keyword, int offset, int size);
    long countOrders(OrderStatus status, String keyword);
    Optional<OrderRecord> findOrder(String orderId);
    Optional<FeatureRecord> findFeature(String orderId);
    ScoreRecord saveScore(FeatureRecord feature, ScoreRecord score);
    List<AlertRecord> findAlerts(AlertStatus status, RiskLevel riskLevel, int offset, int size);
    long countAlerts(AlertStatus status, RiskLevel riskLevel);
    Optional<AlertRecord> findAlert(long alertId);

    /** MySQL: SELECT ... FOR UPDATE；Stub: 与复核共用同一锁。必须在事务内调用。 */
    Optional<AlertRecord> findAlertForUpdate(long alertId);

    List<ReviewRecord> findReviews(long alertId);
    ReviewResponse review(long alertId, String reviewerId, ReviewResult result,
                          String comment, int expectedVersion);

    /**
     * v2 导入投影：stub 模式把外部订单放入内存查询视图；MySQL 模式由 V2Store 直接写 DWD，
     * 默认空实现以避免重复写入。
     */
    default void saveImportedOrder(OrderRecord order) {
        // MySQL mode persists through V2Store.upsertOlistOrder.
    }
}
