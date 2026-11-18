package com.example.fraud.service;

import com.example.fraud.api.ApiException;
import com.example.fraud.domain.OrderRecord;
import com.example.fraud.domain.OrderStatus;
import com.example.fraud.store.FraudStore;
import com.example.fraud.store.V2Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class ImportService {
    private static final DateTimeFormatter OLIST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");
    private static final Set<String> IEEE_NUMERIC_FEATURES = new HashSet<String>(Arrays.asList(
            "card1", "card2", "card3", "card4", "card5", "card6", "addr1", "addr2",
            "dist1", "dist2", "C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10",
            "C11", "C12", "C13", "C14", "D1", "D2", "D3", "D4", "D5", "D6", "D7", "D8",
            "D9", "D10", "D11", "D12", "D13", "D14", "D15"));

    private final V2Store store;
    private final FraudStore fraudStore;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final Clock clock;
    private final String importRoot;

    public ImportService(V2Store store, FraudStore fraudStore, ObjectMapper objectMapper,
                         AuditService auditService, Clock clock,
                         @Value("${app.import-root:data/external}") String importRoot) {
        this.store = store;
        this.fraudStore = fraudStore;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.clock = clock;
        this.importRoot = importRoot;
    }

    public synchronized Map<String, Object> run(String sourceSystem, Authentication authentication,
                                                HttpServletRequest request) {
        String source = sourceSystem == null ? "" : sourceSystem.trim().toUpperCase();
        if (!"OLIST".equals(source) && !"IEEE_CIS".equals(source)) {
            throw ApiException.invalid("sourceSystem 仅支持 OLIST 或 IEEE_CIS");
        }
        Path dir = sourceDir(source);
        List<Path> files = requiredFiles(source, dir);
        String idempotencyKey = idempotencyKey(source, files);
        Map<String, Object> existing = store.findImportByKey(idempotencyKey).orElse(null);
        if (existing != null) {
            Map<String, Object> result = new LinkedHashMap<String, Object>(existing);
            result.put("idempotent", true);
            return result;
        }

        String batchId = "IMP-" + clock.instant().toEpochMilli() + "-"
                + UUID.randomUUID().toString().substring(0, 6);
        String operator = authentication == null ? "system" : authentication.getName();
        Map<String, Object> batch = new LinkedHashMap<String, Object>();
        batch.put("batchId", batchId);
        batch.put("sourceSystem", source);
        batch.put("datasetName", "OLIST".equals(source) ? "olist-brazilian-ecommerce" : "ieee-fraud-detection");
        batch.put("idempotencyKey", idempotencyKey);
        batch.put("status", "RUNNING");
        batch.put("totalRows", 0);
        batch.put("acceptedRows", 0);
        batch.put("rejectedRows", 0);
        batch.put("fileCount", files.size());
        batch.put("operatorId", operator);
        batch.put("errorSummary", null);
        batch.put("startedAt", clock.instant().toString());
        batch.put("finishedAt", null);
        store.insertImport(batch);

        List<Map<String, Object>> checks = new ArrayList<Map<String, Object>>();
        try {
            Counts counts = "OLIST".equals(source)
                    ? importOlist(batchId, dir, checks)
                    : importIeee(batchId, dir, checks);
            batch.put("totalRows", counts.total);
            batch.put("acceptedRows", counts.accepted);
            batch.put("rejectedRows", counts.rejected);
            batch.put("status", counts.accepted == 0 ? "FAILED"
                    : counts.rejected > 0 ? "PARTIAL" : "SUCCEEDED");
            if (counts.rejected > 0) {
                batch.put("errorSummary", "部分行被拒绝：" + counts.rejected);
            }
        } catch (RuntimeException exception) {
            batch.put("status", "FAILED");
            batch.put("errorSummary", "导入失败，请检查文件格式或服务日志");
        }
        batch.put("finishedAt", clock.instant().toString());
        store.updateImport(batch);
        store.saveDqChecks(checks);
        auditService.record(authentication, "IMPORT_RUN", "IMPORT_BATCH", batchId,
                "SUCCEEDED".equals(batch.get("status")) || "PARTIAL".equals(batch.get("status"))
                        ? "SUCCESS" : "FAILED",
                request, countsDetail(batch));
        Map<String, Object> result = new LinkedHashMap<String, Object>(batch);
        result.put("idempotent", false);
        return result;
    }

    private Map<String, Object> countsDetail(Map<String, Object> batch) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("sourceSystem", batch.get("sourceSystem"));
        detail.put("acceptedRows", batch.get("acceptedRows"));
        detail.put("rejectedRows", batch.get("rejectedRows"));
        return detail;
    }

    private Path sourceDir(String source) {
        Path root = importBase();
        Path dir = root.resolve("OLIST".equals(source) ? "olist" : "ieee-cis").normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
            throw ApiException.importSourceMissing(source);
        }
        return dir;
    }

    private Path importBase() {
        Path configured = Paths.get(importRoot).toAbsolutePath().normalize();
        if (Files.isDirectory(configured)) {
            return configured;
        }
        Path fromParent = Paths.get("..").toAbsolutePath().normalize().resolve(importRoot).normalize();
        return Files.isDirectory(fromParent) ? fromParent : configured;
    }

    private List<Path> requiredFiles(String source, Path dir) {
        List<String> names = "OLIST".equals(source)
                ? Arrays.asList("olist_orders_dataset.csv", "olist_order_payments_dataset.csv")
                : Collections.singletonList("train_transaction.csv");
        List<Path> files = new ArrayList<Path>();
        for (String name : names) {
            Path file = dir.resolve(name).normalize();
            if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
                throw ApiException.importSourceMissing(source + " / " + name);
            }
            files.add(file);
        }
        return files;
    }

    private String idempotencyKey(String source, List<Path> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(source.getBytes(StandardCharsets.UTF_8));
            List<Path> sorted = new ArrayList<Path>(files);
            Collections.sort(sorted);
            for (Path file : sorted) {
                digest.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
            return hex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash import files", exception);
        }
    }

    private Counts importOlist(String batchId, Path dir, List<Map<String, Object>> checks) {
        try {
            List<Map<String, String>> orders = Csv.read(dir.resolve("olist_orders_dataset.csv"));
            List<Map<String, String>> payments = Csv.read(dir.resolve("olist_order_payments_dataset.csv"));
            Map<String, String> customerUnique = readCustomers(dir.resolve("olist_customers_dataset.csv"));
            Map<String, List<BigDecimal>> paymentAmounts = new LinkedHashMap<String, List<BigDecimal>>();
            for (Map<String, String> payment : payments) {
                String orderId = trim(payment.get("order_id"));
                BigDecimal value = decimal(payment.get("payment_value"));
                if (orderId == null || value == null) {
                    continue;
                }
                paymentAmounts.computeIfAbsent(orderId, key -> new ArrayList<BigDecimal>()).add(value);
            }
            Counts counts = new Counts();
            Set<String> seen = new HashSet<String>();
            int amountRejected = 0;
            int dateRejected = 0;
            int paymentMissing = 0;
            for (Map<String, String> row : orders) {
                counts.total++;
                String orderId = trim(row.get("order_id"));
                String status = trim(row.get("order_status"));
                String ordered = trim(row.get("order_purchase_timestamp"));
                List<BigDecimal> amounts = orderId == null ? null : paymentAmounts.get(orderId);
                if (orderId == null || status == null || ordered == null || !seen.add(orderId)) {
                    counts.rejected++;
                    continue;
                }
                BigDecimal amount = amounts == null || amounts.isEmpty()
                        ? null : amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                if (amount == null) {
                    paymentMissing++;
                    counts.rejected++;
                    continue;
                }
                if (amount.compareTo(BigDecimal.ZERO) <= 0
                        || amount.compareTo(MAX_AMOUNT) > 0) {
                    amountRejected++;
                    counts.rejected++;
                    continue;
                }
                Instant orderedAt;
                try {
                    orderedAt = LocalDateTime.parse(ordered, OLIST_TIME).toInstant(ZoneOffset.UTC);
                } catch (RuntimeException exception) {
                    dateRejected++;
                    counts.rejected++;
                    continue;
                }
                String customerRef = customerUnique.get(trim(row.get("customer_id")));
                if (customerRef == null) {
                    customerRef = trim(row.get("customer_id"));
                }
                if (customerRef == null) {
                    customerRef = "OLIST-UNKNOWN";
                }
                Map<String, Object> raw = new LinkedHashMap<String, Object>();
                raw.put("orderId", orderId);
                raw.put("status", status);
                raw.put("amount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
                raw.put("currency", "BRL");
                raw.put("paymentCount", amounts.size());
                raw.put("orderedAt", orderedAt.toString());
                raw.put("sourceStatus", status);
                String rawJson = objectMapper.writeValueAsString(raw);
                Map<String, Object> target = new LinkedHashMap<String, Object>();
                target.put("orderId", "OLIST-" + orderId);
                target.put("sourceRecordId", orderId);
                target.put("orderNo", "OLIST-" + orderId);
                target.put("customerRef", customerRef);
                target.put("status", mapOlistStatus(status));
                target.put("sourceStatus", status);
                target.put("amount", amount.setScale(2, RoundingMode.HALF_UP));
                target.put("currency", "BRL");
                target.put("paymentCount", amounts.size());
                target.put("orderedAt", orderedAt.toString());
                target.put("dataOrigin", "OLIST");
                target.put("datasetName", "olist-brazilian-ecommerce");
                target.put("licenseRef", "CC-BY-NC-SA-4.0");
                target.put("batchId", batchId);
                target.put("payloadHash", sha256(rawJson));
                target.put("rawPayload", rawJson);
                store.upsertOlistOrder(target);
                fraudStore.saveImportedOrder(new OrderRecord("OLIST-" + orderId,
                        "OLIST-" + orderId, customerRef, null,
                        OrderStatus.valueOf(mapOlistStatus(status)), amount.setScale(2, RoundingMode.HALF_UP),
                        "BRL", "OLIST", orderedAt));
                counts.accepted++;
            }
            checks.add(check(batchId, "ORDER_ROW_COUNT", "ERROR", counts.total > 0,
                    String.valueOf(counts.total), ">0", "订单总行数"));
            checks.add(check(batchId, "ORDER_PAYMENT_COVERAGE", "WARN",
                    counts.total > 0 && (counts.total - paymentMissing) * 100 >= counts.total * 95,
                    String.valueOf(counts.total - paymentMissing), ">=95% 有支付记录",
                    "缺失支付记录的订单"));
            checks.add(check(batchId, "ORDER_AMOUNT_POSITIVE", "ERROR", amountRejected == 0,
                    String.valueOf(amountRejected), "0", "金额必须大于 0"));
            checks.add(check(batchId, "ORDER_DATE_VALID", "ERROR", dateRejected == 0,
                    String.valueOf(dateRejected), "0", "下单时间必须可解析"));
            Map<String, String> nulls = new LinkedHashMap<String, String>();
            nulls.put("accountCreatedAt", "外部源无此字段，保持 NULL，不造值");
            checks.add(check(batchId, "ORDER_FEATURE_GAPS", "INFO", true,
                    "accountCreatedAt=NULL,transactionCount24h=NULL", "不造值",
                    "Olist 不提供账户创建时间与实时频次"));
            return counts;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read Olist files", exception);
        }
    }

    private Map<String, String> readCustomers(Path file) throws IOException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        for (Map<String, String> row : Csv.read(file)) {
            String id = trim(row.get("customer_id"));
            String unique = trim(row.get("customer_unique_id"));
            if (id != null && unique != null) {
                result.put(id, unique);
            }
        }
        return result;
    }

    private Counts importIeee(String batchId, Path dir, List<Map<String, Object>> checks) {
        try {
            List<Map<String, String>> rows = Csv.read(dir.resolve("train_transaction.csv"));
            Counts counts = new Counts();
            Set<Long> seen = new HashSet<Long>();
            int badLabel = 0;
            int badAmount = 0;
            for (Map<String, String> row : rows) {
                counts.total++;
                Long transactionId = longValue(row.get("TransactionID"));
                Long transactionDt = longValue(row.get("TransactionDT"));
                BigDecimal amount = decimal(row.get("TransactionAmt"));
                Integer label = integer(row.get("isFraud"));
                if (transactionId == null || transactionDt == null || amount == null
                        || label == null || !seen.add(transactionId)) {
                    counts.rejected++;
                    continue;
                }
                if (label != 0 && label != 1) {
                    badLabel++;
                    counts.rejected++;
                    continue;
                }
                if (amount.compareTo(BigDecimal.ZERO) < 0
                        || amount.compareTo(MAX_AMOUNT) > 0) {
                    badAmount++;
                    counts.rejected++;
                    continue;
                }
                BigDecimal normalizedAmount = amount.setScale(2, RoundingMode.HALF_UP);
                Map<String, Object> features = new LinkedHashMap<String, Object>();
                features.put("TransactionAmt", normalizedAmount);
                for (String feature : IEEE_NUMERIC_FEATURES) {
                    BigDecimal value = decimal(row.get(feature));
                    if (value != null) {
                        features.put(feature, value);
                    }
                }
                features.put("hour", (transactionDt / 3600) % 24);
                features.put("dayOfWeek", (transactionDt / 86400) % 7);
                Map<String, Object> target = new LinkedHashMap<String, Object>();
                target.put("transactionId", transactionId);
                target.put("datasetName", "ieee-fraud-detection");
                target.put("transactionDt", transactionDt);
                target.put("transactionAmt", normalizedAmount);
                target.put("isFraud", label);
                target.put("featureJson", features);
                target.put("batchId", batchId);
                store.upsertIeeeTransaction(target);
                counts.accepted++;
            }
            checks.add(check(batchId, "IEEE_ROW_COUNT", "ERROR", counts.total > 0,
                    String.valueOf(counts.total), ">0", "交易总行数"));
            checks.add(check(batchId, "IEEE_LABEL_BINARY", "ERROR", badLabel == 0,
                    String.valueOf(badLabel), "0", "isFraud 仅允许 0/1"));
            checks.add(check(batchId, "IEEE_AMOUNT_NON_NEGATIVE", "ERROR", badAmount == 0,
                    String.valueOf(badAmount), "0", "交易金额不得为负"));
            checks.add(check(batchId, "IEEE_TIME_SPLIT_READY", "INFO", true,
                    "TransactionDT 为相对时间", "按时间切分", "不得伪造为真实日期"));
            return counts;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read IEEE-CIS files", exception);
        }
    }

    private Map<String, Object> check(String batchId, String code, String severity, boolean passed,
                                      String observed, String expected, String detail) {
        Map<String, Object> check = new LinkedHashMap<String, Object>();
        check.put("batchId", batchId);
        check.put("checkCode", code);
        check.put("severity", severity);
        check.put("passed", passed);
        check.put("observed", observed);
        check.put("expected", expected);
        check.put("detail", detail);
        return check;
    }

    private String mapOlistStatus(String status) {
        if ("canceled".equals(status) || "unavailable".equals(status)) {
            return "CANCELLED";
        }
        if ("created".equals(status)) {
            return "CREATED";
        }
        return "PAID";
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BigDecimal decimal(String value) {
        String trimmed = trim(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Long longValue(String value) {
        String trimmed = trim(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Long.valueOf(trimmed);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Integer integer(String value) {
        String trimmed = trim(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash payload", exception);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static final class Counts {
        private int total;
        private int accepted;
        private int rejected;
    }
}
