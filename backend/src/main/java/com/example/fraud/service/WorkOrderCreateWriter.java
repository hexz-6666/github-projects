package com.example.fraud.service;

import com.example.fraud.api.ApiException;
import com.example.fraud.domain.AlertRecord;
import com.example.fraud.domain.AlertStatus;
import com.example.fraud.domain.OrderRecord;
import com.example.fraud.domain.WorkOrderConfig;
import com.example.fraud.domain.WorkOrderConfigSnapshot;
import com.example.fraud.domain.WorkOrderConfigStatus;
import com.example.fraud.domain.WorkOrderConfigVersion;
import com.example.fraud.domain.WorkOrderEventRecord;
import com.example.fraud.domain.WorkOrderEventType;
import com.example.fraud.domain.WorkOrderRecord;
import com.example.fraud.domain.WorkOrderStatus;
import com.example.fraud.store.FraudStore;
import com.example.fraud.store.WorkOrderConfigStore;
import com.example.fraud.store.WorkOrderStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工单创建的唯一事务边界。
 *
 * <p>告警行和配置行在同一个事务内按固定顺序锁定，但检查、插入 work_order、
 * CREATED 事件和审计仍由本方法统一提交/回滚。唯一键冲突必须回滚本事务，
 * 再由 WorkOrderService 在事务外查询已有工单，避免 rollback-only 事务内继续读。</p>
 */
@Service
public class WorkOrderCreateWriter {
    private final WorkOrderStore store;
    private final WorkOrderConfigStore configStore;
    private final FraudStore fraudStore;
    private final WorkOrderMatcher matcher;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkOrderCreateWriter(WorkOrderStore store, WorkOrderConfigStore configStore,
                                 FraudStore fraudStore, WorkOrderMatcher matcher,
                                 AuditService auditService, ObjectMapper objectMapper, Clock clock) {
        this.store = store;
        this.configStore = configStore;
        this.fraudStore = fraudStore;
        this.matcher = matcher;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public static class Result {
        private final WorkOrderRecord record;
        private final boolean idempotent;

        public Result(WorkOrderRecord record, boolean idempotent) {
            this.record = record;
            this.idempotent = idempotent;
        }

        public WorkOrderRecord getRecord() { return record; }
        public boolean isIdempotent() { return idempotent; }
    }

    @Transactional
    public Result create(long alertId, long configId, String actorId, String actorRole,
                         String requestId) {
        if ("stub".equals(store.storageMode())) {
            // Stub has no transaction manager; serialize the check+insert window
            // so the local contract demo has the same observable semantics.
            synchronized (this) {
                return createInTransaction(alertId, configId, actorId, actorRole, requestId);
            }
        }
        return createInTransaction(alertId, configId, actorId, actorRole, requestId);
    }

    private Result createInTransaction(long alertId, long configId, String actorId, String actorRole,
                                       String requestId) {
        AlertRecord alert = fraudStore.findAlertForUpdate(alertId)
                .orElseThrow(ApiException::alertNotFound);

        // Idempotency is checked before any mutable alert/config state validation.
        // A previously created work order stays idempotent even if its alert was
        // resolved or its config was disabled after creation.
        WorkOrderRecord existing = store.findByAlertId(alertId).orElse(null);
        if (existing != null) {
            if (existing.getConfigId() != configId) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "WORK_ORDER_ALREADY_EXISTS_DIFFERENT_CONFIG",
                        "该告警已使用其他配置创建工单");
            }
            return new Result(existing, true);
        }

        if (alert.getAlertStatus() != AlertStatus.OPEN && alert.getAlertStatus() != AlertStatus.REVIEWING) {
            throw new ApiException(HttpStatus.CONFLICT, "ALERT_NOT_ACTIONABLE",
                    "只有 OPEN 或 REVIEWING 告警可以转工单");
        }

        WorkOrderConfig config = configStore.findConfigForUpdate(configId)
                .orElseThrow(WorkOrderConfigService::configNotFound);
        if (config.getStatus() == WorkOrderConfigStatus.DISABLED) {
            throw WorkOrderConfigService.configDisabled();
        }
        if (config.getStatus() != WorkOrderConfigStatus.ACTIVE || config.getPublishedVersion() == null) {
            throw WorkOrderConfigService.configNotPublished();
        }
        WorkOrderConfigVersion version = configStore.findVersion(configId, config.getPublishedVersion())
                .orElseThrow(WorkOrderConfigService::configNotPublished);
        WorkOrderConfigSnapshot snapshot = version.toSnapshot();
        OrderRecord order = fraudStore.findOrder(alert.getOrderId()).orElse(null);
        if (!matcher.matches(alert, order, snapshot)) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFIG_NOT_MATCHED",
                    "配置未匹配当前告警，不能创建工单");
        }

        Instant now = clock.instant();
        WorkOrderRecord pending = new WorkOrderRecord(0L, alertId, configId, version.getId(),
                version.getVersionNo(), json(snapshot), snapshot.getAssignRole(), null,
                WorkOrderStatus.OPEN, snapshot.isRequiresReview(), snapshot.getSlaMinutes(),
                now.plusSeconds(snapshot.getSlaMinutes() * 60L), null, null, null,
                1, actorId, actorId, now, now);
        long id = store.insert(pending);
        WorkOrderRecord saved = pending.withId(id);
        store.insertEvent(new WorkOrderEventRecord(null, id, WorkOrderEventType.CREATED,
                null, WorkOrderStatus.OPEN, actorId, actorRole, null, requestId, clock.instant()));

        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("alertId", saved.getAlertId());
        detail.put("configId", saved.getConfigId());
        detail.put("configVersionNo", saved.getConfigVersionNo());
        detail.put("status", saved.getStatus().name());
        auditService.recordActor(actorId, actorRole, "WORK_ORDER_CREATED", "WORK_ORDER",
                String.valueOf(id), "SUCCESS", requestId, detail);
        return new Result(saved, false);
    }

    private String json(WorkOrderConfigSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot serialize work order config snapshot", exception);
        }
    }
}
