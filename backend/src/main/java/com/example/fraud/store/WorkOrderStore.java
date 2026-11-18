package com.example.fraud.store;

import com.example.fraud.domain.WorkOrderEventRecord;
import com.example.fraud.domain.WorkOrderRecord;
import com.example.fraud.domain.WorkOrderScope;
import com.example.fraud.domain.WorkOrderStatus;
import java.util.List;
import java.util.Optional;

public interface WorkOrderStore {
    String storageMode();

    long insert(WorkOrderRecord workOrder);

    Optional<WorkOrderRecord> findByAlertId(long alertId);

    Optional<WorkOrderRecord> findById(long id);

    /** Stub: synchronized read; MySQL: SELECT ... FOR UPDATE，必须在事务内调用。 */
    Optional<WorkOrderRecord> findByIdForUpdate(long id);

    List<WorkOrderRecord> findWorkOrders(WorkOrderScope scope, String username, String role,
                                         WorkOrderStatus status, int offset, int size);

    long countWorkOrders(WorkOrderScope scope, String username, String role, WorkOrderStatus status);

    /** WHERE id=? AND version=? AND status=?；返回 0 表示并发冲突。 */
    int update(WorkOrderRecord workOrder, int expectedVersion, WorkOrderStatus expectedStatus);

    long insertEvent(WorkOrderEventRecord event);

    List<WorkOrderEventRecord> findEvents(long workOrderId);
}
