package com.example.fraud.store;

import com.example.fraud.domain.WorkOrderConfig;
import com.example.fraud.domain.WorkOrderConfigStatus;
import com.example.fraud.domain.WorkOrderConfigVersion;
import java.util.ArrayList;
import java.util.Collections;
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
public class StubWorkOrderConfigStore implements WorkOrderConfigStore {
    private final Map<Long, WorkOrderConfig> configs = new LinkedHashMap<Long, WorkOrderConfig>();
    private final Map<Long, WorkOrderConfigVersion> versions = new LinkedHashMap<Long, WorkOrderConfigVersion>();
    private final AtomicLong configIds = new AtomicLong(1);
    private final AtomicLong versionIds = new AtomicLong(1);

    @Override
    public String storageMode() {
        return "stub";
    }

    @Override
    public synchronized Optional<WorkOrderConfig> findConfig(long id) {
        return Optional.ofNullable(configs.get(id));
    }

    @Override
    public synchronized Optional<WorkOrderConfig> findConfigForUpdate(long id) {
        return Optional.ofNullable(configs.get(id));
    }

    @Override
    public synchronized List<WorkOrderConfig> findConfigs(String keyword, WorkOrderConfigStatus status,
                                                          boolean publishedOnly, int offset, int size) {
        String normalized = keyword == null ? null : keyword.trim().toLowerCase();
        List<WorkOrderConfig> matches = configs.values().stream()
                .filter(config -> status == null || config.getStatus() == status)
                .filter(config -> !publishedOnly || config.getPublishedVersion() != null)
                .filter(config -> normalized == null || normalized.isEmpty()
                        || config.getCode().toLowerCase().contains(normalized)
                        || config.getName().toLowerCase().contains(normalized))
                .sorted(Comparator.comparing(WorkOrderConfig::getUpdatedAt).reversed()
                        .thenComparing(Comparator.comparingLong(WorkOrderConfig::getId).reversed()))
                .collect(Collectors.toList());
        int from = Math.min(Math.max(0, offset), matches.size());
        int to = Math.min(from + Math.max(0, size), matches.size());
        return new ArrayList<WorkOrderConfig>(matches.subList(from, to));
    }

    @Override
    public synchronized long countConfigs(String keyword, WorkOrderConfigStatus status, boolean publishedOnly) {
        String normalized = keyword == null ? null : keyword.trim().toLowerCase();
        return configs.values().stream()
                .filter(config -> status == null || config.getStatus() == status)
                .filter(config -> !publishedOnly || config.getPublishedVersion() != null)
                .filter(config -> normalized == null || normalized.isEmpty()
                        || config.getCode().toLowerCase().contains(normalized)
                        || config.getName().toLowerCase().contains(normalized))
                .count();
    }

    @Override
    public synchronized boolean codeExists(String code) {
        return configs.values().stream().anyMatch(config -> config.getCode().equals(code));
    }

    @Override
    public synchronized long insertConfig(WorkOrderConfig config) {
        if (codeExists(config.getCode())) {
            throw new org.springframework.dao.DuplicateKeyException("work order config code exists");
        }
        long id = configIds.getAndIncrement();
        configs.put(id, config.withId(id));
        return id;
    }

    @Override
    public synchronized int updateConfig(WorkOrderConfig config, int expectedVersion) {
        WorkOrderConfig current = configs.get(config.getId());
        if (current == null || current.getVersion() != expectedVersion) {
            return 0;
        }
        configs.put(config.getId(), config);
        return 1;
    }

    @Override
    public synchronized Optional<WorkOrderConfigVersion> findVersion(long configId, int versionNo) {
        return versions.values().stream()
                .filter(version -> version.getConfigId() == configId && version.getVersionNo() == versionNo)
                .findFirst();
    }

    @Override
    public synchronized List<WorkOrderConfigVersion> findVersions(long configId) {
        List<WorkOrderConfigVersion> result = versions.values().stream()
                .filter(version -> version.getConfigId() == configId)
                .sorted(Comparator.comparingInt(WorkOrderConfigVersion::getVersionNo))
                .collect(Collectors.toList());
        return result;
    }

    @Override
    public synchronized int nextVersionNo(long configId) {
        int max = 0;
        for (WorkOrderConfigVersion version : versions.values()) {
            if (version.getConfigId() == configId) {
                max = Math.max(max, version.getVersionNo());
            }
        }
        return max + 1;
    }

    @Override
    public synchronized long insertVersion(WorkOrderConfigVersion version) {
        long id = versionIds.getAndIncrement();
        versions.put(id, version.withId(id));
        return id;
    }

    @Override
    public synchronized List<WorkOrderConfig> findActivePublishedConfigs() {
        List<WorkOrderConfig> result = configs.values().stream()
                .filter(config -> config.getStatus() == WorkOrderConfigStatus.ACTIVE)
                .filter(config -> config.getPublishedVersion() != null)
                .sorted(Comparator.comparingLong(WorkOrderConfig::getId))
                .collect(Collectors.toList());
        return Collections.unmodifiableList(result);
    }
}
