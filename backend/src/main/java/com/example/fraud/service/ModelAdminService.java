package com.example.fraud.service;

import com.example.fraud.api.ApiException;
import com.example.fraud.store.V2Store;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class ModelAdminService {
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private final V2Store store;
    private final ModelAdminClient client;
    private final AuditService auditService;
    private final String importRoot;

    public ModelAdminService(V2Store store, ModelAdminClient client, AuditService auditService,
                             @Value("${app.import-root:data/external}") String importRoot) {
        this.store = store;
        this.client = client;
        this.auditService = auditService;
        this.importRoot = importRoot;
    }

    public Map<String, Object> train(String modelVersion, Authentication authentication,
                                     HttpServletRequest request) {
        if (modelVersion == null || !VERSION.matcher(modelVersion).matches()
                || "synthetic-rule-v1".equals(modelVersion)) {
            throw ApiException.invalid("modelVersion 格式不合法，且不能复用 DEMO_RULE 版本");
        }
        Path root = Paths.get(importRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            Path fromParent = Paths.get("..").toAbsolutePath().normalize().resolve(importRoot).normalize();
            if (Files.isDirectory(fromParent)) {
                root = fromParent;
            }
        }
        Path data = root.resolve("ieee-cis").resolve("train_transaction.csv").normalize();
        if (!data.startsWith(root) || !Files.isRegularFile(data)) {
            throw ApiException.importSourceMissing("IEEE_CIS / train_transaction.csv");
        }
        Map<String, Object> response = client.train(modelVersion, data.toString());
        String responseVersion = String.valueOf(response.get("modelVersion"));
        String modelType = String.valueOf(response.get("modelType"));
        String sourceSystem = String.valueOf(response.get("sourceSystem"));
        String artifactPath = String.valueOf(response.get("artifactPath"));
        Object metricsValue = response.get("metrics");
        if (!modelVersion.equals(responseVersion) || !"IEEE_CIS".equals(sourceSystem)
                || modelType == null || !modelType.startsWith("TRAINED_")
                || artifactPath.contains("..") || !artifactPath.startsWith("runtime/models/")
                || !(metricsValue instanceof Map)) {
            throw ApiException.invalidModelResponse();
        }
        Path artifact = Paths.get(artifactPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(artifact)) {
            Path fromParent = Paths.get("..").toAbsolutePath().normalize().resolve(artifactPath).normalize();
            if (Files.isRegularFile(fromParent)) {
                artifact = fromParent;
            }
        }
        Path artifactRoot = Paths.get("runtime/models").toAbsolutePath().normalize();
        Path artifactRootFromParent = Paths.get("..").toAbsolutePath().normalize()
                .resolve("runtime/models").normalize();
        if ((!artifact.startsWith(artifactRoot) && !artifact.startsWith(artifactRootFromParent))
                || !Files.isRegularFile(artifact)) {
            throw ApiException.invalidModelResponse();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) metricsValue;
        Map<String, Object> model = new LinkedHashMap<String, Object>();
        model.put("modelVersion", modelVersion);
        model.put("modelType", modelType);
        model.put("sourceSystem", sourceSystem);
        model.put("artifactPath", artifactPath);
        model.put("featureSchema", response.get("featureSchema"));
        model.put("trainedAt", response.get("trainedAt"));
        model.put("trainingRows", integer(response.get("trainingRows")));
        model.put("positiveRate", decimal(response.get("positiveRate")));
        model.put("status", "ACTIVE");
        store.saveModel(model);

        Map<String, Object> evaluation = new LinkedHashMap<String, Object>();
        evaluation.put("modelVersion", modelVersion);
        evaluation.put("sourceSystem", sourceSystem);
        Map<String, Object> split = metrics.get("split") instanceof Map
                ? (Map<String, Object>) metrics.get("split") : new LinkedHashMap<String, Object>();
        evaluation.put("splitStrategy", split.get("strategy") == null
                ? "TIME_70_15_15" : split.get("strategy"));
        evaluation.put("entityGrouping", split.get("entityGrouping") == null
                ? "NONE" : split.get("entityGrouping"));
        evaluation.put("validRowsDroppedForEntity", integerOrZero(split.get("validRowsDroppedForEntity")));
        evaluation.put("testRowsDroppedForEntity", integerOrZero(split.get("testRowsDroppedForEntity")));
        evaluation.put("trainRows", integer(metrics.get("trainRows")));
        evaluation.put("validRows", integer(metrics.get("validRows")));
        evaluation.put("testRows", integer(metrics.get("testRows")));
        evaluation.put("testPositives", integer(metrics.get("testPositives")));
        evaluation.put("baseRate", decimal(metrics.get("baseRate")));
        evaluation.put("prAuc", decimal(metrics.get("prAuc")));
        evaluation.put("rocAuc", decimal(metrics.get("rocAuc")));
        evaluation.put("recallAtFpr1", decimal(metrics.get("recallAtFpr1")));
        evaluation.put("recallAtFpr5", decimal(metrics.get("recallAtFpr5")));
        evaluation.put("precisionAt100", decimal(metrics.get("precisionAt100")));
        evaluation.put("brier", decimal(metrics.get("brier")));
        evaluation.put("threshold", decimal(metrics.get("threshold")));
        evaluation.put("confusion", metrics.get("confusion"));
        store.saveEvaluation(evaluation);

        auditService.record(authentication, "MODEL_TRAIN", "MODEL", modelVersion, "SUCCESS",
                request, countsDetail(model, metrics));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("model", model);
        result.put("evaluation", evaluation);
        result.put("labelSemantics", "MODEL_SCORE_NOT_GROUND_TRUTH");
        return result;
    }

    public List<Map<String, Object>> models() {
        return store.findModels();
    }

    public List<Map<String, Object>> evaluations() {
        return store.findEvaluations();
    }

    private Map<String, Object> countsDetail(Map<String, Object> model, Map<String, Object> metrics) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("modelType", model.get("modelType"));
        detail.put("trainingRows", model.get("trainingRows"));
        detail.put("prAuc", metrics.get("prAuc"));
        return detail;
    }

    private Integer integer(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private Integer integerOrZero(Object value) {
        Integer parsed = integer(value);
        return parsed == null ? Integer.valueOf(0) : parsed;
    }

    private java.math.BigDecimal decimal(Object value) {
        if (value instanceof java.math.BigDecimal) {
            return (java.math.BigDecimal) value;
        }
        return value instanceof Number ? new java.math.BigDecimal(value.toString()) : null;
    }
}
