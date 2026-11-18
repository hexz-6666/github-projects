package com.example.fraud.store;

import com.example.fraud.domain.WorkOrderConfig;
import com.example.fraud.domain.WorkOrderConfigStatus;
import com.example.fraud.domain.WorkOrderConfigVersion;
import java.util.List;
import java.util.Optional;

public interface WorkOrderConfigStore {
    String storageMode();

    Optional<WorkOrderConfig> findConfig(long id);

    /** Stub: synchronized read; MySQL: SELECT ... FOR UPDATE，必须在事务内调用。 */
    Optional<WorkOrderConfig> findConfigForUpdate(long id);

    List<WorkOrderConfig> findConfigs(String keyword, WorkOrderConfigStatus status,
                                      boolean publishedOnly, int offset, int size);

    long countConfigs(String keyword, WorkOrderConfigStatus status, boolean publishedOnly);

    boolean codeExists(String code);

    long insertConfig(WorkOrderConfig config);

    /** 返回 0 表示 expectedVersion 已过期。 */
    int updateConfig(WorkOrderConfig config, int expectedVersion);

    Optional<WorkOrderConfigVersion> findVersion(long configId, int versionNo);

    List<WorkOrderConfigVersion> findVersions(long configId);

    int nextVersionNo(long configId);

    long insertVersion(WorkOrderConfigVersion version);

    List<WorkOrderConfig> findActivePublishedConfigs();
}
