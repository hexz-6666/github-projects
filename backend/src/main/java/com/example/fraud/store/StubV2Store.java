package com.example.fraud.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!mysql")
public class StubV2Store implements V2Store {
    private final List<Map<String, Object>> audit = new CopyOnWriteArrayList<Map<String, Object>>();
    private final List<Map<String, Object>> imports = new CopyOnWriteArrayList<Map<String, Object>>();
    private final List<Map<String, Object>> dq = new CopyOnWriteArrayList<Map<String, Object>>();
    private final List<Map<String, Object>> models = new CopyOnWriteArrayList<Map<String, Object>>();
    private final List<Map<String, Object>> evaluations = new CopyOnWriteArrayList<Map<String, Object>>();
    private final Map<Long, Map<String, Object>> ieee = new LinkedHashMap<Long, Map<String, Object>>();
    private final Map<String, Map<String, Object>> olist = new LinkedHashMap<String, Map<String, Object>>();

    @Override
    public String storageMode() { return "stub"; }

    @Override
    public void appendAudit(Map<String, Object> event) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>(event);
        if (!copy.containsKey("createdAt")) {
            copy.put("createdAt", Instant.now().toString());
        }
        audit.add(copy);
    }

    @Override
    public List<Map<String, Object>> findAudit(int offset, int size) {
        List<Map<String, Object>> copy = new ArrayList<Map<String, Object>>(audit);
        Collections.reverse(copy);
        return slice(copy, offset, size);
    }

    @Override
    public long countAudit() { return audit.size(); }

    @Override
    public Optional<Map<String, Object>> findImportByKey(String idempotencyKey) {
        synchronized (imports) {
            return imports.stream().filter(row -> idempotencyKey.equals(row.get("idempotencyKey")))
                    .findFirst().map(LinkedHashMap::new);
        }
    }

    @Override
    public void insertImport(Map<String, Object> batch) {
        synchronized (imports) {
            imports.add(new LinkedHashMap<String, Object>(batch));
        }
    }

    @Override
    public void updateImport(Map<String, Object> batch) {
        synchronized (imports) {
            for (int i = 0; i < imports.size(); i++) {
                if (imports.get(i).get("batchId").equals(batch.get("batchId"))) {
                    imports.set(i, new LinkedHashMap<String, Object>(batch));
                    return;
                }
            }
        }
    }

    @Override
    public Optional<Map<String, Object>> findImport(String batchId) {
        synchronized (imports) {
            return imports.stream().filter(row -> batchId.equals(row.get("batchId")))
                    .findFirst().map(LinkedHashMap::new);
        }
    }

    @Override
    public List<Map<String, Object>> findImports(int offset, int size) {
        List<Map<String, Object>> copy;
        synchronized (imports) {
            copy = new ArrayList<Map<String, Object>>(imports);
        }
        copy.sort(Comparator.comparing((Map<String, Object> row) ->
                String.valueOf(row.get("startedAt"))).reversed());
        List<Map<String, Object>> page = slice(copy, offset, size);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : page) {
            result.add(new LinkedHashMap<String, Object>(row));
        }
        return result;
    }

    @Override
    public long countImports() { return imports.size(); }

    @Override
    public void saveDqChecks(List<Map<String, Object>> checks) {
        for (Map<String, Object> check : checks) {
            dq.add(new LinkedHashMap<String, Object>(check));
        }
    }

    @Override
    public List<Map<String, Object>> findDqChecks(String batchId) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : dq) {
            if (batchId.equals(row.get("batchId"))) {
                result.add(new LinkedHashMap<String, Object>(row));
            }
        }
        return result;
    }

    @Override
    public void upsertOlistOrder(Map<String, Object> row) {
        synchronized (olist) {
            olist.put(String.valueOf(row.get("orderId")), new LinkedHashMap<String, Object>(row));
        }
    }

    @Override
    public void upsertIeeeTransaction(Map<String, Object> row) {
        synchronized (ieee) {
            ieee.put(Long.valueOf(String.valueOf(row.get("transactionId"))),
                    new LinkedHashMap<String, Object>(row));
        }
    }

    @Override
    public long countIeeeTransactions() { return ieee.size(); }

    @Override
    public void saveModel(Map<String, Object> model) {
        models.add(new LinkedHashMap<String, Object>(model));
    }

    @Override
    public List<Map<String, Object>> findModels() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : models) {
            result.add(new LinkedHashMap<String, Object>(row));
        }
        return result;
    }

    @Override
    public void saveEvaluation(Map<String, Object> evaluation) {
        evaluations.add(new LinkedHashMap<String, Object>(evaluation));
    }

    @Override
    public List<Map<String, Object>> findEvaluations() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : evaluations) {
            result.add(new LinkedHashMap<String, Object>(row));
        }
        return result;
    }

    private List<Map<String, Object>> slice(List<Map<String, Object>> rows, int offset, int size) {
        if (offset >= rows.size()) {
            return Collections.emptyList();
        }
        int end = Math.min(rows.size(), offset + size);
        return rows.subList(offset, end);
    }
}
