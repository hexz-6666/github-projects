package com.example.fraud.service;

import com.example.fraud.api.ApiException;
import com.example.fraud.api.PageResponse;
import com.example.fraud.domain.AlertRecord;
import com.example.fraud.domain.AlertStatus;
import com.example.fraud.domain.OrderRecord;
import com.example.fraud.domain.ReviewRecord;
import com.example.fraud.domain.ReviewResult;
import com.example.fraud.domain.SlaStatus;
import com.example.fraud.domain.WorkOrderConfig;
import com.example.fraud.domain.WorkOrderConfigVersion;
import com.example.fraud.domain.WorkOrderEventRecord;
import com.example.fraud.domain.WorkOrderEventType;
import com.example.fraud.domain.WorkOrderRecord;
import com.example.fraud.domain.WorkOrderScope;
import com.example.fraud.domain.WorkOrderStatus;
import com.example.fraud.store.DuplicateWorkOrderException;
import com.example.fraud.store.FraudStore;
import com.example.fraud.store.WorkOrderConfigStore;
import com.example.fraud.store.WorkOrderStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderService {
    private final FraudStore fraudStore;
    private final WorkOrderConfigStore configStore;
    private final WorkOrderStore workOrderStore;
    private final WorkOrderConfigService configService;
    private final WorkOrderMatcher matcher;
    private final WorkOrderCreateWriter createWriter;
    private final AssigneeDirectory assigneeDirectory;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkOrderService(FraudStore fraudStore, WorkOrderConfigStore configStore,
                            WorkOrderStore workOrderStore, WorkOrderConfigService configService,
                            WorkOrderMatcher matcher, WorkOrderCreateWriter createWriter,
                            AssigneeDirectory assigneeDirectory, AuditService auditService,
                            ObjectMapper objectMapper, Clock clock) {
        this.fraudStore = fraudStore;
        this.configStore = configStore;
        this.workOrderStore = workOrderStore;
        this.configService = configService;
        this.matcher = matcher;
        this.createWriter = createWriter;
        this.assigneeDirectory = assigneeDirectory;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public static class CreateResult {
        private final WorkOrderRecord record;
        private final boolean idempotent;

        public CreateResult(WorkOrderRecord record, boolean idempotent) {
            this.record = record;
            this.idempotent = idempotent;
        }

        public WorkOrderRecord getRecord() { return record; }
        public boolean isIdempotent() { return idempotent; }
    }

    public CreateResult create(long alertId, long configId, Authentication authentication,
                               HttpServletRequest servletRequest) {
        requireAlert(alertId);
        String actor = actor(authentication);
        String role = auditService.roleName(authentication);
        String requestId = auditService.requestId(servletRequest);
        // Auxiliary check only: the writer still owns locking, idempotency and
        // transaction boundaries. This keeps an invisible existing work order
        // from reaching the different-config 409 branch.
        requireExistingVisible(alertId, authentication);
        try {
            WorkOrderCreateWriter.Result result = createWriter.create(alertId, configId, actor, role, requestId);
            // The writer result is a snapshot. Re-read the current row by id so
            // a concurrent assignment cannot make a stale unassigned snapshot
            // visible to a caller who no longer owns the work order.
            WorkOrderRecord fresh = requireFreshVisibleForCreate(result.getRecord().getId(), authentication);
            return new CreateResult(fresh, result.isIdempotent());
        } catch (DuplicateWorkOrderException exception) {
            // Defensive fallback for a unique-key race outside the normal
            // alert-row lock path. This read happens after the writer transaction
            // has rolled back, so it is not inside a rollback-only transaction.
            WorkOrderRecord existing = workOrderStore.findByAlertId(alertId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                            "WORK_ORDER_ALREADY_EXISTS", "该告警已存在工单"));
            WorkOrderRecord fresh = requireFreshVisibleForCreate(existing.getId(), authentication);
            if (fresh.getConfigId() != configId) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "WORK_ORDER_ALREADY_EXISTS_DIFFERENT_CONFIG",
                        "该告警已使用其他配置创建工单");
            }
            return new CreateResult(fresh, true);
        } catch (ApiException exception) {
            // The writer throws this exact code after it has observed an
            // existing work order. Re-read that row and convert to 404 when it
            // is currently invisible; only a visible current record may reveal
            // the 409 conflict.
            if ("WORK_ORDER_ALREADY_EXISTS_DIFFERENT_CONFIG".equals(exception.getCode())) {
                WorkOrderRecord existing = workOrderStore.findByAlertId(alertId).orElse(null);
                if (existing != null) {
                    requireFreshVisibleForCreate(existing.getId(), authentication);
                }
            }
            throw exception;
        }
    }

    private void requireExistingVisible(long alertId, Authentication authentication) {
        WorkOrderRecord existing = workOrderStore.findByAlertId(alertId).orElse(null);
        if (existing != null && !canView(existing, authentication)) {
            throw notFound();
        }
    }

    private WorkOrderRecord requireFreshVisibleForCreate(long id, Authentication authentication) {
        WorkOrderRecord fresh = workOrderStore.findById(id).orElseThrow(WorkOrderService::notFound);
        if (!canView(fresh, authentication)) {
            throw notFound();
        }
        return fresh;
    }

    public PageResponse<Map<String, Object>> list(WorkOrderScope scope, WorkOrderStatus status,
                                                  int page, int size, Authentication authentication) {
        boolean admin = isAdmin(authentication);
        if (scope == WorkOrderScope.ALL && !admin) {
            throw ApiException.forbidden("只有 ADMIN 可以查看全部工单");
        }
        String username = actor(authentication);
        String role = auditService.roleName(authentication);
        long total = workOrderStore.countWorkOrders(scope, username, role, status);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (WorkOrderRecord record : workOrderStore.findWorkOrders(scope, username, role, status,
                page * size, size)) {
            items.add(summary(record));
        }
        return new PageResponse<Map<String, Object>>(items, page, size, total);
    }

    public Map<String, Object> detail(long id, Authentication authentication) {
        WorkOrderRecord record = requireVisible(id, authentication);
        return buildDetail(record, authentication);
    }

    public List<Map<String, Object>> alertWorkOrders(long alertId, Authentication authentication) {
        requireAlert(alertId);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        // UNIQUE(alert_id) means there is at most one work order. Query by the
        // alert key instead of taking the newest 100 work orders and filtering
        // in memory; otherwise an older work order would silently disappear.
        WorkOrderRecord record = workOrderStore.findByAlertId(alertId).orElse(null);
        if (record != null && canView(record, authentication)) {
            result.add(summary(record));
        }
        return result;
    }

    public Map<String, Object> candidates(long alertId, Authentication authentication) {
        AlertRecord alert = requireAlert(alertId);
        boolean actionable = isActionable(alert);
        List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
        if (actionable) {
            OrderRecord order = fraudStore.findOrder(alert.getOrderId()).orElse(null);
            for (WorkOrderConfig config : configStore.findActivePublishedConfigs()) {
                WorkOrderConfigVersion version = configStore.findVersion(config.getId(),
                        config.getPublishedVersion()).orElse(null);
                if (version == null || !matcher.matches(alert, order, version.toSnapshot())) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("configId", config.getId());
                item.put("code", config.getCode());
                item.put("name", version.getName());
                item.put("publishedVersion", version.getVersionNo());
                item.put("priority", version.getPriority());
                item.put("slaMinutes", version.getSlaMinutes());
                item.put("requiresReview", version.isRequiresReview());
                item.put("assignRole", version.getAssignRole());
                item.put("riskScoreThreshold", version.getRiskScoreThreshold());
                item.put("riskThresholdSemantics", WorkOrderConfigService.THRESHOLD_SEMANTICS);
                candidates.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("alertId", alertId);
        result.put("actionable", actionable);
        result.put("alertStatus", alert.getAlertStatus().name());
        result.put("candidates", candidates);
        WorkOrderRecord existing = workOrderStore.findByAlertId(alertId).orElse(null);
        result.put("existingWorkOrder", existing == null || !canView(existing, authentication)
                ? null : summary(existing));
        return result;
    }

    @Transactional
    public Map<String, Object> assign(long id, String assigneeId, int expectedVersion,
                                      Authentication authentication, HttpServletRequest request) {
        WorkOrderRecord current = requireForUpdate(id);
        requireNotTerminal(current);
        if (current.getStatus() != WorkOrderStatus.OPEN && current.getStatus() != WorkOrderStatus.ASSIGNED) {
            throw invalidTransition(current.getStatus(), WorkOrderStatus.ASSIGNED);
        }
        String actor = actor(authentication);
        String assignee = assigneeId == null ? null : assigneeId.trim();
        if (assignee == null || assignee.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "assigneeId 不能为空");
        }
        String targetRole;
        boolean roleOverride = false;
        if (isAdmin(authentication)) {
            targetRole = assigneeDirectory.roleOf(assignee)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                            "WORK_ORDER_ASSIGNEE_INVALID", "目标处理人不是有效 REVIEWER/ADMIN"));
            roleOverride = !targetRole.equals(current.getAssignRole());
            if (current.getStatus() == WorkOrderStatus.ASSIGNED && assignee.equals(current.getAssigneeId())) {
                throw new ApiException(HttpStatus.CONFLICT, "WORK_ORDER_ALREADY_ASSIGNED",
                        "工单已分派给该处理人");
            }
        } else {
            if (!isReviewer(authentication)) {
                throw ApiException.forbidden("当前账号不能分派工单");
            }
            if (!assignee.equals(actor)) {
                throw ApiException.forbidden("REVIEWER 只能分派给自己");
            }
            if (!"REVIEWER".equals(current.getAssignRole())) {
                throw ApiException.forbidden("该工单不允许 REVIEWER 分派");
            }
            if (current.getStatus() == WorkOrderStatus.ASSIGNED) {
                throw new ApiException(HttpStatus.CONFLICT, "WORK_ORDER_ALREADY_ASSIGNED",
                        "工单已被领取");
            }
            targetRole = "REVIEWER";
        }
        WorkOrderEventType eventType = current.getAssigneeId() == null
                ? WorkOrderEventType.ASSIGNED : WorkOrderEventType.REASSIGNED;
        WorkOrderRecord updated = current.withState(assignee, WorkOrderStatus.ASSIGNED,
                current.getResolutionReviewId(), current.getResolvedAt(), current.getCancelledAt(),
                current.getVersion() + 1, actor, clock.instant());
        if (workOrderStore.update(updated, expectedVersion, current.getStatus()) == 0) {
            throw versionConflict();
        }
        recordEvent(updated, eventType, current.getStatus(), WorkOrderStatus.ASSIGNED, actor,
                auditService.roleName(authentication), null, auditService.requestId(request));
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("assigneeId", assignee);
        detail.put("roleOverride", roleOverride);
        detail.put("fromStatus", current.getStatus().name());
        detail.put("toStatus", WorkOrderStatus.ASSIGNED.name());
        audit(authentication, eventType == WorkOrderEventType.REASSIGNED
                ? "WORK_ORDER_REASSIGNED" : "WORK_ORDER_ASSIGNED", id, request, detail);
        return detail(id, authentication);
    }

    @Transactional
    public Map<String, Object> transition(long id, String transition, Integer expectedVersion,
                                          String note, Long resolutionReviewId,
                                          Authentication authentication, HttpServletRequest request) {
        WorkOrderRecord current = requireForUpdate(id);
        if (current.getStatus().isTerminal()) {
            throw terminal(current.getStatus());
        }
        String action = transition == null ? "" : transition.trim().toUpperCase();
        String actor = actor(authentication);
        String role = auditService.roleName(authentication);
        String requestId = auditService.requestId(request);
        WorkOrderRecord updated;
        WorkOrderEventType eventType;
        WorkOrderStatus oldStatus = current.getStatus();
        if ("CLAIM".equals(action)) {
            if (oldStatus != WorkOrderStatus.OPEN) {
                throw invalidTransition(oldStatus, WorkOrderStatus.ASSIGNED);
            }
            if (!isReviewer(authentication) && !isAdmin(authentication)) {
                throw ApiException.forbidden("当前账号不能领取工单");
            }
            if (isReviewer(authentication) && !"REVIEWER".equals(current.getAssignRole())) {
                throw ApiException.forbidden("该工单不允许 REVIEWER 领取");
            }
            boolean roleOverride = isAdmin(authentication) && !"ADMIN".equals(current.getAssignRole());
            updated = current.withState(actor, WorkOrderStatus.ASSIGNED, null, null, null,
                    current.getVersion() + 1, actor, clock.instant());
            eventType = WorkOrderEventType.CLAIMED;
            if (workOrderStore.update(updated, expectedVersion, oldStatus) == 0) {
                throw versionConflict();
            }
            recordEvent(updated, eventType, oldStatus, WorkOrderStatus.ASSIGNED, actor, role,
                    null, requestId);
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("assigneeId", actor);
            detail.put("roleOverride", roleOverride);
            audit(authentication, "WORK_ORDER_CLAIMED", id, request, detail);
        } else if ("START".equals(action)) {
            if (oldStatus != WorkOrderStatus.ASSIGNED) {
                throw invalidTransition(oldStatus, WorkOrderStatus.IN_PROGRESS);
            }
            requireHandler(current, authentication);
            String normalizedNote = normalizeNote(note, false);
            updated = current.withState(current.getAssigneeId(), WorkOrderStatus.IN_PROGRESS,
                    null, null, null, current.getVersion() + 1, actor, clock.instant());
            eventType = WorkOrderEventType.STARTED;
            if (workOrderStore.update(updated, expectedVersion, oldStatus) == 0) {
                throw versionConflict();
            }
            recordEvent(updated, eventType, oldStatus, WorkOrderStatus.IN_PROGRESS, actor, role,
                    normalizedNote, requestId);
            audit(authentication, "WORK_ORDER_STARTED", id, request, statusDetail(oldStatus,
                    WorkOrderStatus.IN_PROGRESS));
        } else if ("RESOLVE".equals(action)) {
            if (oldStatus != WorkOrderStatus.IN_PROGRESS) {
                throw invalidTransition(oldStatus, WorkOrderStatus.RESOLVED);
            }
            requireHandler(current, authentication);
            String normalizedNote = normalizeNote(note, true);
            ReviewRecord review = validateResolutionReview(current, resolutionReviewId);
            Instant now = clock.instant();
            updated = current.withState(current.getAssigneeId(), WorkOrderStatus.RESOLVED,
                    review == null ? null : review.getReviewId(), now, null,
                    current.getVersion() + 1, actor, now);
            eventType = WorkOrderEventType.RESOLVED;
            if (workOrderStore.update(updated, expectedVersion, oldStatus) == 0) {
                throw versionConflict();
            }
            recordEvent(updated, eventType, oldStatus, WorkOrderStatus.RESOLVED, actor, role,
                    normalizedNote, requestId);
            Map<String, Object> detail = statusDetail(oldStatus, WorkOrderStatus.RESOLVED);
            detail.put("resolutionReviewId", updated.getResolutionReviewId());
            audit(authentication, "WORK_ORDER_RESOLVED", id, request, detail);
        } else if ("CANCEL".equals(action)) {
            if (!isAdmin(authentication)) {
                throw ApiException.forbidden("只有 ADMIN 可以取消工单");
            }
            String normalizedNote = normalizeNote(note, true);
            Instant now = clock.instant();
            updated = current.withState(current.getAssigneeId(), WorkOrderStatus.CANCELLED,
                    current.getResolutionReviewId(), null, now, current.getVersion() + 1,
                    actor, now);
            eventType = WorkOrderEventType.CANCELLED;
            if (workOrderStore.update(updated, expectedVersion, oldStatus) == 0) {
                throw versionConflict();
            }
            recordEvent(updated, eventType, oldStatus, WorkOrderStatus.CANCELLED, actor, role,
                    normalizedNote, requestId);
            audit(authentication, "WORK_ORDER_CANCELLED", id, request,
                    statusDetail(oldStatus, WorkOrderStatus.CANCELLED));
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_WORK_ORDER_TRANSITION",
                    "不支持的工单动作");
        }
        return detail(id, authentication);
    }

    @Transactional
    public Map<String, Object> addNote(long id, String note, int expectedVersion,
                                       Authentication authentication, HttpServletRequest request) {
        WorkOrderRecord current = requireForUpdate(id);
        if (current.getStatus().isTerminal()) {
            throw terminal(current.getStatus());
        }
        if (!isAdmin(authentication) && !actor(authentication).equals(current.getAssigneeId())) {
            throw ApiException.forbidden("只有当前处理人或 ADMIN 可以添加处理备注");
        }
        String normalizedNote = normalizeNote(note, true);
        String actor = actor(authentication);
        WorkOrderRecord updated = current.withState(current.getAssigneeId(), current.getStatus(),
                current.getResolutionReviewId(), current.getResolvedAt(), current.getCancelledAt(),
                current.getVersion() + 1, actor, clock.instant());
        if (workOrderStore.update(updated, expectedVersion, current.getStatus()) == 0) {
            throw versionConflict();
        }
        recordEvent(updated, WorkOrderEventType.NOTE_ADDED, current.getStatus(), current.getStatus(),
                actor, auditService.roleName(authentication), normalizedNote,
                auditService.requestId(request));
        audit(authentication, "WORK_ORDER_NOTE_ADDED", id, request, statusDetail(current.getStatus(),
                current.getStatus()));
        return detail(id, authentication);
    }

    public List<Map<String, Object>> assignees(Authentication authentication) {
        if (!isAdmin(authentication)) {
            throw ApiException.forbidden("只有 ADMIN 可以查看可指派账号");
        }
        return assigneeDirectory.list();
    }

    public Map<String, Object> createDetail(long id, Authentication authentication) {
        // This path is used only by the create/idempotency response. It always
        // re-reads the latest row, then applies the create-specific 404 rule;
        // ordinary detail(id) keeps its existing 403 through requireVisible.
        WorkOrderRecord fresh = requireFreshVisibleForCreate(id, authentication);
        return buildDetail(fresh, authentication);
    }

    private Map<String, Object> buildDetail(WorkOrderRecord record, Authentication authentication) {
        AlertRecord alert = requireAlert(record.getAlertId());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", record.getId());
        result.put("alertId", record.getAlertId());
        result.put("alert", alertSummary(alert));
        result.put("configId", record.getConfigId());
        result.put("configVersionId", record.getConfigVersionId());
        result.put("configVersionNo", record.getConfigVersionNo());
        result.put("configSnapshot", snapshotMap(record.getConfigSnapshotJson()));
        result.put("assignRole", record.getAssignRole());
        result.put("assigneeId", record.getAssigneeId());
        result.put("status", record.getStatus().name());
        result.put("requiresReview", record.isRequiresReview());
        result.put("slaMinutes", record.getSlaMinutes());
        result.put("dueAt", record.getDueAt());
        result.put("slaStatus", SlaStatus.of(record, clock).name());
        result.put("resolutionReviewId", record.getResolutionReviewId());
        result.put("resolvedAt", record.getResolvedAt());
        result.put("cancelledAt", record.getCancelledAt());
        result.put("closedAt", record.getResolvedAt() != null ? record.getResolvedAt()
                : record.getCancelledAt());
        result.put("version", record.getVersion());
        result.put("createdBy", record.getCreatedBy());
        result.put("updatedBy", record.getUpdatedBy());
        result.put("createdAt", record.getCreatedAt());
        result.put("updatedAt", record.getUpdatedAt());
        result.put("events", events(record.getId()));
        List<Map<String, Object>> reviewCandidates = resolutionCandidates(record);
        result.put("resolutionReviewCandidates", reviewCandidates);
        result.put("resolutionReview", resolutionReview(record, reviewCandidates));
        Map<String, Object> actions = actions(record, authentication);
        result.put("actions", actions);
        return result;
    }

    private Map<String, Object> actions(WorkOrderRecord record, Authentication authentication) {
        boolean terminal = record.getStatus().isTerminal();
        boolean admin = isAdmin(authentication);
        boolean reviewer = isReviewer(authentication);
        boolean assignedToMe = actor(authentication).equals(record.getAssigneeId());
        boolean canHandle = admin || assignedToMe;
        boolean open = record.getStatus() == WorkOrderStatus.OPEN;
        boolean assignable = record.getStatus() == WorkOrderStatus.OPEN
                || record.getStatus() == WorkOrderStatus.ASSIGNED;

        Map<String, Object> actions = new LinkedHashMap<String, Object>();
        actions.put("canAssign", !terminal && admin && assignable);
        actions.put("canAssignSelf", !terminal && open
                && reviewer && "REVIEWER".equals(record.getAssignRole()));
        actions.put("canClaim", !terminal && open
                && (admin || (reviewer && "REVIEWER".equals(record.getAssignRole()))));
        actions.put("canStart", !terminal && record.getStatus() == WorkOrderStatus.ASSIGNED && canHandle);
        actions.put("canResolve", !terminal && record.getStatus() == WorkOrderStatus.IN_PROGRESS && canHandle);
        actions.put("canCancel", !terminal && admin);
        actions.put("canAddNote", !terminal && canHandle);
        return actions;
    }

    private Map<String, Object> summary(WorkOrderRecord record) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", record.getId());
        result.put("alertId", record.getAlertId());
        result.put("configId", record.getConfigId());
        Map<String, Object> snapshot = snapshotMap(record.getConfigSnapshotJson());
        result.put("configCode", snapshot.get("code"));
        result.put("configName", snapshot.get("name"));
        result.put("configVersionNo", record.getConfigVersionNo());
        result.put("assignRole", record.getAssignRole());
        result.put("assigneeId", record.getAssigneeId());
        result.put("status", record.getStatus().name());
        result.put("requiresReview", record.isRequiresReview());
        result.put("slaMinutes", record.getSlaMinutes());
        result.put("dueAt", record.getDueAt());
        result.put("slaStatus", SlaStatus.of(record, clock).name());
        result.put("resolutionReviewId", record.getResolutionReviewId());
        result.put("resolvedAt", record.getResolvedAt());
        result.put("cancelledAt", record.getCancelledAt());
        result.put("closedAt", record.getResolvedAt() != null ? record.getResolvedAt()
                : record.getCancelledAt());
        result.put("version", record.getVersion());
        result.put("createdAt", record.getCreatedAt());
        return result;
    }

    private Map<String, Object> alertSummary(AlertRecord alert) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("alertId", alert.getAlertId());
        result.put("orderId", alert.getOrderId());
        result.put("alertStatus", alert.getAlertStatus().name());
        result.put("riskLevel", alert.getRiskLevel().name());
        result.put("score", alert.getScore().getScore());
        result.put("reasonCodes", alert.getReasonCodes());
        result.put("version", alert.getVersion());
        result.put("createdAt", alert.getCreatedAt());
        return result;
    }

    private List<Map<String, Object>> events(long workOrderId) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (WorkOrderEventRecord event : workOrderStore.findEvents(workOrderId)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("eventId", event.getEventId());
            item.put("eventType", event.getEventType().name());
            item.put("fromStatus", event.getFromStatus() == null ? null : event.getFromStatus().name());
            item.put("toStatus", event.getToStatus() == null ? null : event.getToStatus().name());
            item.put("actorId", event.getActorId());
            item.put("actorRole", event.getActorRole());
            item.put("note", event.getNote());
            item.put("createdAt", event.getCreatedAt());
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> resolutionCandidates(WorkOrderRecord record) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ReviewRecord review : fraudStore.findReviews(record.getAlertId())) {
            if (!isConclusiveReview(record, review)) {
                continue;
            }
            result.add(reviewSummary(review));
        }
        return result;
    }

    private boolean isConclusiveReview(WorkOrderRecord record, ReviewRecord review) {
        return review.getAlertId() == record.getAlertId()
                && (review.getReviewResult() == ReviewResult.CONFIRMED
                || review.getReviewResult() == ReviewResult.REJECTED)
                && review.getCreatedAt() != null
                && !review.getCreatedAt().isBefore(record.getCreatedAt());
    }

    private Map<String, Object> resolutionReview(WorkOrderRecord record, List<Map<String, Object>> candidates) {
        if (record.getResolutionReviewId() == null) {
            return null;
        }
        for (Map<String, Object> candidate : candidates) {
            if (record.getResolutionReviewId().equals(candidate.get("reviewId"))) {
                return candidate;
            }
        }
        return null;
    }

    private Map<String, Object> reviewSummary(ReviewRecord review) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("reviewId", review.getReviewId());
        item.put("reviewerId", review.getReviewerId());
        item.put("reviewResult", review.getReviewResult().name());
        item.put("createdAt", review.getCreatedAt());
        return item;
    }

    private ReviewRecord validateResolutionReview(WorkOrderRecord workOrder, Long reviewId) {
        if (reviewId == null) {
            if (workOrder.isRequiresReview()) {
                throw new ApiException(HttpStatus.CONFLICT, "RESOLUTION_REVIEW_REQUIRED",
                        "该工单需要结论性复核记录");
            }
            return null;
        }
        ReviewRecord review = fraudStore.findReviews(workOrder.getAlertId()).stream()
                .filter(item -> item.getReviewId() == reviewId)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "RESOLUTION_REVIEW_NOT_FOUND", "复核记录不存在或不属于当前告警"));
        if (review.getAlertId() != workOrder.getAlertId()) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOLUTION_REVIEW_ALERT_MISMATCH",
                    "复核记录不属于当前告警");
        }
        if (review.getReviewResult() == ReviewResult.INCONCLUSIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOLUTION_REVIEW_INCONCLUSIVE",
                    "INCONCLUSIVE 复核不能用于完成工单");
        }
        if (review.getCreatedAt() == null || review.getCreatedAt().isBefore(workOrder.getCreatedAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOLUTION_REVIEW_PREDATES_WORK_ORDER",
                    "复核记录必须创建于工单创建之后");
        }
        return review;
    }

    private void recordEvent(WorkOrderRecord record, WorkOrderEventType eventType,
                             WorkOrderStatus fromStatus, WorkOrderStatus toStatus,
                             String actorId, String actorRole, String note, String requestId) {
        workOrderStore.insertEvent(new WorkOrderEventRecord(null, record.getId(), eventType,
                fromStatus, toStatus, actorId, actorRole, note, requestId, clock.instant()));
    }

    private Map<String, Object> snapshotMap(String json) {
        if (json == null) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("invalid work order config snapshot", exception);
        }
    }

    private Map<String, Object> statusDetail(WorkOrderStatus from, WorkOrderStatus to) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("fromStatus", from.name());
        detail.put("toStatus", to.name());
        return detail;
    }

    private void audit(Authentication authentication, String action, long targetId,
                       HttpServletRequest request, Map<String, Object> detail) {
        auditService.recordActor(actor(authentication), auditService.roleName(authentication), action,
                "WORK_ORDER", String.valueOf(targetId), "SUCCESS",
                auditService.requestId(request), detail);
    }

    private WorkOrderRecord requireForUpdate(long id) {
        return workOrderStore.findByIdForUpdate(id).orElseThrow(WorkOrderService::notFound);
    }

    private WorkOrderRecord requireVisible(long id, Authentication authentication) {
        WorkOrderRecord record = workOrderStore.findById(id).orElseThrow(WorkOrderService::notFound);
        if (!canView(record, authentication)) {
            throw ApiException.forbidden("不能查看其他处理人的工单");
        }
        return record;
    }

    private boolean canView(WorkOrderRecord record, Authentication authentication) {
        return isAdmin(authentication) || record.getAssigneeId() == null
                || actor(authentication).equals(record.getAssigneeId());
    }

    private void requireNotTerminal(WorkOrderRecord record) {
        if (record.getStatus().isTerminal()) {
            throw terminal(record.getStatus());
        }
    }

    private void requireHandler(WorkOrderRecord record, Authentication authentication) {
        if (!isAdmin(authentication) && !actor(authentication).equals(record.getAssigneeId())) {
            throw ApiException.forbidden("只有当前处理人或 ADMIN 可以执行该动作");
        }
    }

    private boolean isActionable(AlertRecord alert) {
        return alert.getAlertStatus() == AlertStatus.OPEN || alert.getAlertStatus() == AlertStatus.REVIEWING;
    }

    private String normalizeNote(String note, boolean required) {
        String value = note == null ? null : note.trim();
        if (value == null || value.isEmpty()) {
            if (required) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NOTE_REQUIRED", "备注不能为空");
            }
            return null;
        }
        if (value.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOTE_TOO_LONG", "备注不能超过 500 字符");
        }
        return value;
    }

    private AlertRecord requireAlert(long alertId) {
        return fraudStore.findAlert(alertId).orElseThrow(ApiException::alertNotFound);
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private boolean isReviewer(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_REVIEWER".equals(authority.getAuthority()));
    }

    private boolean isTerminal(WorkOrderStatus status) {
        return status.isTerminal();
    }

    private String actor(Authentication authentication) {
        return authentication == null ? "anonymous" : authentication.getName();
    }

    private ApiException invalidTransition(WorkOrderStatus from, WorkOrderStatus to) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_WORK_ORDER_TRANSITION",
                "不允许从 " + from.name() + " 转为 " + to.name());
    }

    private ApiException terminal(WorkOrderStatus status) {
        return new ApiException(HttpStatus.CONFLICT, "WORK_ORDER_TERMINAL",
                "工单已处于终态 " + status.name());
    }

    private ApiException versionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "WORK_ORDER_VERSION_CONFLICT",
                "工单版本已变化，请刷新后重试");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "WORK_ORDER_NOT_FOUND", "工单不存在");
    }
}
