package com.example.fraud.store;

import com.example.fraud.domain.WorkOrderEventRecord;
import com.example.fraud.domain.WorkOrderRecord;
import com.example.fraud.domain.WorkOrderScope;
import com.example.fraud.domain.WorkOrderStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!mysql")
public class StubWorkOrderStore implements WorkOrderStore {
    private final Map<Long, WorkOrderRecord> workOrders = new LinkedHashMap<Long, WorkOrderRecord>();
    private final Map<Long, List<WorkOrderEventRecord>> events = new LinkedHashMap<Long, List<WorkOrderEventRecord>>();
    private final AtomicLong workOrderIds = new AtomicLong(1);
    private final AtomicLong eventIds = new AtomicLong(1);

    @Override
    public String storageMode() {
        return "stub";
    }

    @Override
    public synchronized long insert(WorkOrderRecord workOrder) {
        for (WorkOrderRecord existing : workOrders.values()) {
            if (existing.getAlertId() == workOrder.getAlertId()) {
                throw new DuplicateWorkOrderException();
            }
        }
        long id = workOrderIds.getAndIncrement();
        workOrders.put(id, workOrder.withId(id));
        return id;
    }

    @Override
    public synchronized Optional<WorkOrderRecord> findByAlertId(long alertId) {
        return workOrders.values().stream()
                .filter(workOrder -> workOrder.getAlertId() == alertId)
                .findFirst();
    }

    @Override
    public synchronized Optional<WorkOrderRecord> findById(long id) {
        return Optional.ofNullable(workOrders.get(id));
    }

    @Override
    public synchronized Optional<WorkOrderRecord> findByIdForUpdate(long id) {
        return Optional.ofNullable(workOrders.get(id));
    }

    @Override
    public synchronized List<WorkOrderRecord> findWorkOrders(WorkOrderScope scope, String username,
                                                             String role, WorkOrderStatus status,
                                                             int offset, int size) {
        List<WorkOrderRecord> matches = workOrders.values().stream()
                .filter(workOrder -> filter(workOrder, scope, username, role))
                .filter(workOrder -> status == null || workOrder.getStatus() == status)
                .sorted(Comparator.comparing(WorkOrderRecord::getCreatedAt).reversed()
                        .thenComparing(Comparator.comparingLong(WorkOrderRecord::getId).reversed()))
                .collect(Collectors.toList());
        int from = Math.min(Math.max(0, offset), matches.size());
        int to = Math.min(from + Math.max(0, size), matches.size());
        return new ArrayList<WorkOrderRecord>(matches.subList(from, to));
    }

    @Override
    public synchronized long countWorkOrders(WorkOrderScope scope, String username, String role,
                                             WorkOrderStatus status) {
        return workOrders.values().stream()
                .filter(workOrder -> filter(workOrder, scope, username, role))
                .filter(workOrder -> status == null || workOrder.getStatus() == status)
                .count();
    }

    private boolean filter(WorkOrderRecord workOrder, WorkOrderScope scope, String username, String role) {
        if (scope == WorkOrderScope.MINE) {
            return username != null && username.equals(workOrder.getAssigneeId());
        }
        if (scope == WorkOrderScope.ALL) {
            return true;
        }
        if (workOrder.getAssigneeId() != null || workOrder.getStatus() != WorkOrderStatus.OPEN) {
            return false;
        }
        return !"REVIEWER".equals(role) || "REVIEWER".equals(workOrder.getAssignRole());
    }

    @Override
    public synchronized int update(WorkOrderRecord workOrder, int expectedVersion,
                                   WorkOrderStatus expectedStatus) {
        WorkOrderRecord current = workOrders.get(workOrder.getId());
        if (current == null || current.getVersion() != expectedVersion || current.getStatus() != expectedStatus) {
            return 0;
        }
        workOrders.put(workOrder.getId(), workOrder);
        return 1;
    }

    @Override
    public synchronized long insertEvent(WorkOrderEventRecord event) {
        long id = eventIds.getAndIncrement();
        events.computeIfAbsent(event.getWorkOrderId(), key -> new ArrayList<WorkOrderEventRecord>())
                .add(event.withId(id));
        return id;
    }

    @Override
    public synchronized List<WorkOrderEventRecord> findEvents(long workOrderId) {
        List<WorkOrderEventRecord> result = events.getOrDefault(workOrderId, new ArrayList<WorkOrderEventRecord>());
        return new ArrayList<WorkOrderEventRecord>(result);
    }
}
