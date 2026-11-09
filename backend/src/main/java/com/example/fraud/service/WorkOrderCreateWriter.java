package com.example.fraud.service;

import com.example.fraud.domain.WorkOrderEventRecord;
import com.example.fraud.domain.WorkOrderEventType;
import com.example.fraud.domain.WorkOrderRecord;
import com.example.fraud.domain.WorkOrderStatus;
import com.example.fraud.store.WorkOrderStore;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在独立事务中完成“工单 + CREATED 事件 + 审计”三写。
 * 唯一键冲突必须让本事务回滚，再由 WorkOrderService 在事务外查询已有工单。
 */
@Service
public class WorkOrderCreateWriter {
    private final WorkOrderStore store;
    private final AuditService auditService;
    private final Clock clock;

    public WorkOrderCreateWriter(WorkOrderStore store, AuditService auditService, Clock clock) {
        this.store = store;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public WorkOrderRecord create(WorkOrderRecord pending, String actorId, String actorRole,
                                  String requestId) {
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
        return saved;
    }
}
