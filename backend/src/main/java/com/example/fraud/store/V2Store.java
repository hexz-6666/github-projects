package com.example.fraud.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v2 增量存储：导入批次、数据质量、审计、模型注册/评测与 IEEE 独立评测域。
 * 使用 Map 承载非敏感元数据，避免为演示扩散大量 DTO；所有字段均为服务端生成。
 */
public interface V2Store {
    String storageMode();

    void appendAudit(Map<String, Object> event);
    List<Map<String, Object>> findAudit(int offset, int size);
    long countAudit();

    Optional<Map<String, Object>> findImportByKey(String idempotencyKey);
    void insertImport(Map<String, Object> batch);
    void updateImport(Map<String, Object> batch);
    Optional<Map<String, Object>> findImport(String batchId);
    List<Map<String, Object>> findImports(int offset, int size);
    long countImports();

    void saveDqChecks(List<Map<String, Object>> checks);
    List<Map<String, Object>> findDqChecks(String batchId);

    void upsertOlistOrder(Map<String, Object> row);
    void upsertIeeeTransaction(Map<String, Object> row);
    long countIeeeTransactions();

    void saveModel(Map<String, Object> model);
    List<Map<String, Object>> findModels();
    void saveEvaluation(Map<String, Object> evaluation);
    List<Map<String, Object>> findEvaluations();
}
