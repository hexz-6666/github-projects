package com.example.fraud.service;

import com.example.fraud.api.ApiException;
import com.example.fraud.api.PageResponse;
import com.example.fraud.api.WorkOrderConfigCreateRequest;
import com.example.fraud.api.WorkOrderConfigUpdateRequest;
import com.example.fraud.api.WorkOrderTriggerRequest;
import com.example.fraud.domain.WorkOrderConfig;
import com.example.fraud.domain.WorkOrderConfigSnapshot;
import com.example.fraud.domain.WorkOrderConfigStatus;
import com.example.fraud.domain.WorkOrderConfigVersion;
import com.example.fraud.domain.WorkOrderTrigger;
import com.example.fraud.store.WorkOrderConfigStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderConfigService {
    public static final String THRESHOLD_SEMANTICS = "风险分阈值（非欺诈概率）";

    private final WorkOrderConfigStore store;
    private final WorkOrderMatcher matcher;
    private final AuditService auditService;
    private final Clock clock;

    public WorkOrderConfigService(WorkOrderConfigStore store, WorkOrderMatcher matcher,
                                  AuditService auditService, Clock clock) {
        this.store = store;
        this.matcher = matcher;
        this.auditService = auditService;
        this.clock = clock;
    }

    public PageResponse<Map<String, Object>> list(String keyword, WorkOrderConfigStatus status,
                                                  int page, int size, Authentication authentication) {
        boolean admin = isAdmin(authentication);
        if (!admin) {
            // 非管理员只能按“已发布快照”的 code/name 搜索，避免通过草稿名称推断未发布内容。
            String normalized = keyword == null ? "" : keyword.trim().toLowerCase();
            List<WorkOrderConfig> visible = new ArrayList<WorkOrderConfig>();
            for (WorkOrderConfig config : store.findConfigs(null, status, true, 0, Integer.MAX_VALUE)) {
                WorkOrderConfigSnapshot snapshot = publishedSnapshot(config);
                if (normalized.isEmpty()
                        || snapshot.getCode().toLowerCase().contains(normalized)
                        || snapshot.getName().toLowerCase().contains(normalized)) {
                    visible.add(config);
                }
            }
            int from = Math.min(Math.max(0, page * size), visible.size());
            int to = Math.min(from + size, visible.size());
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (WorkOrderConfig config : visible.subList(from, to)) {
                items.add(toResponse(config, authentication));
            }
            return new PageResponse<Map<String, Object>>(items, page, size, visible.size());
        }
        long total = store.countConfigs(keyword, status, false);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (WorkOrderConfig config : store.findConfigs(keyword, status, false, page * size, size)) {
            items.add(toResponse(config, authentication));
        }
        return new PageResponse<Map<String, Object>>(items, page, size, total);
    }

    public Map<String, Object> get(long id, Authentication authentication) {
        WorkOrderConfig config = requireConfig(id);
        if (!isAdmin(authentication) && config.getPublishedVersion() == null) {
            throw configNotFound();
        }
        return toResponse(config, authentication);
    }

    public List<Map<String, Object>> versions(long id, Authentication authentication) {
        WorkOrderConfig config = requireConfig(id);
        if (!isAdmin(authentication) && config.getPublishedVersion() == null) {
            throw configNotFound();
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (WorkOrderConfigVersion version : store.findVersions(id)) {
            result.add(versionResponse(version));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> create(WorkOrderConfigCreateRequest request, Authentication authentication,
                                      HttpServletRequest servletRequest) {
        if (store.codeExists(request.getCode())) {
            throw codeExists();
        }
        WorkOrderTrigger trigger = matcher.validate(request.getTrigger());
        Instant now = clock.instant();
        String actor = actor(authentication);
        WorkOrderConfig config = new WorkOrderConfig(0L, request.getCode(), request.getName().trim(),
                trimToNull(request.getDescription()), request.getPriority(), trigger,
                request.getRiskScoreThreshold(), request.getSlaMinutes(), request.getRequiresReview(),
                request.getAssignRole(), WorkOrderConfigStatus.DRAFT, 1, null, actor, actor, now, now);
        long id;
        try {
            id = store.insertConfig(config);
        } catch (DuplicateKeyException exception) {
            throw codeExists();
        }
        audit(authentication, "WORK_ORDER_CONFIG_CREATED", id, servletRequest,
                detail("code", config.getCode(), "status", config.getStatus().name()));
        return toResponse(requireConfig(id), authentication);
    }

    @Transactional
    public Map<String, Object> update(long id, WorkOrderConfigUpdateRequest request,
                                      Authentication authentication, HttpServletRequest servletRequest) {
        WorkOrderConfig current = requireConfig(id);
        if (current.getStatus() == WorkOrderConfigStatus.DISABLED) {
            throw configDisabled();
        }
        if (current.getVersion() != request.getExpectedVersion()) {
            throw versionConflict();
        }
        WorkOrderTrigger trigger = matcher.validate(request.getTrigger());
        Instant now = clock.instant();
        String actor = actor(authentication);
        WorkOrderConfig updated = current.withUpdate(request.getName().trim(),
                trimToNull(request.getDescription()), request.getPriority(), trigger,
                request.getRiskScoreThreshold(), request.getSlaMinutes(), request.getRequiresReview(),
                request.getAssignRole(), current.getStatus(), request.getExpectedVersion() + 1,
                current.getPublishedVersion(), actor, now);
        if (store.updateConfig(updated, request.getExpectedVersion()) == 0) {
            throw versionConflict();
        }
        audit(authentication, "WORK_ORDER_CONFIG_UPDATED", id, servletRequest,
                detail("status", updated.getStatus().name(), "version", updated.getVersion()));
        return toResponse(requireConfig(id), authentication);
    }

    @Transactional
    public Map<String, Object> publish(long id, int expectedVersion, Authentication authentication,
                                       HttpServletRequest servletRequest) {
        WorkOrderConfig current = requireConfigForUpdate(id);
        if (current.getStatus() == WorkOrderConfigStatus.DISABLED) {
            throw configDisabled();
        }
        if (current.getVersion() != expectedVersion) {
            throw versionConflict();
        }
        int versionNo = store.nextVersionNo(id);
        Instant now = clock.instant();
        String actor = actor(authentication);
        WorkOrderConfigVersion version = new WorkOrderConfigVersion(0L, id, versionNo,
                expectedVersion + 1, current.getCode(), current.getName(), current.getDescription(),
                current.getPriority(), current.getTrigger(), current.getRiskScoreThreshold(),
                current.getSlaMinutes(), current.isRequiresReview(), current.getAssignRole(),
                actor, now, now);
        // 先写不可变快照，再移动 published_version；MySQL 的复合外键要求被引用行先存在。
        store.insertVersion(version);
        WorkOrderConfig updated = current.withUpdate(current.getName(), current.getDescription(),
                current.getPriority(), current.getTrigger(), current.getRiskScoreThreshold(),
                current.getSlaMinutes(), current.isRequiresReview(), current.getAssignRole(),
                WorkOrderConfigStatus.ACTIVE, expectedVersion + 1, versionNo, actor, now);
        if (store.updateConfig(updated, expectedVersion) == 0) {
            throw versionConflict();
        }
        audit(authentication, "WORK_ORDER_CONFIG_PUBLISHED", id, servletRequest,
                detail("publishedVersion", versionNo, "status", "ACTIVE"));
        return toResponse(requireConfig(id), authentication);
    }

    @Transactional
    public Map<String, Object> disable(long id, int expectedVersion, Authentication authentication,
                                       HttpServletRequest servletRequest) {
        WorkOrderConfig current = requireConfigForUpdate(id);
        if (current.getStatus() != WorkOrderConfigStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFIG_INVALID_STATE",
                    "只有 ACTIVE 配置可以停用");
        }
        if (current.getVersion() != expectedVersion) {
            throw versionConflict();
        }
        Instant now = clock.instant();
        WorkOrderConfig updated = current.withUpdate(current.getName(), current.getDescription(),
                current.getPriority(), current.getTrigger(), current.getRiskScoreThreshold(),
                current.getSlaMinutes(), current.isRequiresReview(), current.getAssignRole(),
                WorkOrderConfigStatus.DISABLED, expectedVersion + 1, current.getPublishedVersion(),
                actor(authentication), now);
        if (store.updateConfig(updated, expectedVersion) == 0) {
            throw versionConflict();
        }
        audit(authentication, "WORK_ORDER_CONFIG_DISABLED", id, servletRequest,
                detail("publishedVersion", current.getPublishedVersion(), "status", "DISABLED"));
        return toResponse(requireConfig(id), authentication);
    }

    public Map<String, Object> preview(long configId) {
        WorkOrderConfig config = requireConfig(configId);
        if (config.getStatus() == WorkOrderConfigStatus.DISABLED) {
            throw configDisabled();
        }
        if (config.getStatus() != WorkOrderConfigStatus.ACTIVE || config.getPublishedVersion() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFIG_NOT_PUBLISHED",
                    "配置尚未发布，不能预览；请先发布");
        }
        WorkOrderConfigVersion version = store.findVersion(configId, config.getPublishedVersion())
                .orElseThrow(WorkOrderConfigService::configNotPublished);
        return matcher.preview(version.toSnapshot());
    }

    public WorkOrderConfigSnapshot publishedSnapshot(WorkOrderConfig config) {
        if (config.getPublishedVersion() == null) {
            throw configNotPublished();
        }
        return store.findVersion(config.getId(), config.getPublishedVersion())
                .map(WorkOrderConfigVersion::toSnapshot)
                .orElseThrow(WorkOrderConfigService::configNotPublished);
    }

    private Map<String, Object> toResponse(WorkOrderConfig config, Authentication authentication) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", config.getId());
        result.put("code", config.getCode());
        result.put("status", config.getStatus().name());
        result.put("enabled", config.getStatus() == WorkOrderConfigStatus.ACTIVE);
        result.put("publishedVersion", config.getPublishedVersion());
        result.put("riskThresholdSemantics", THRESHOLD_SEMANTICS);
        WorkOrderConfigVersion published = config.getPublishedVersion() == null ? null
                : store.findVersion(config.getId(), config.getPublishedVersion()).orElse(null);
        if (isAdmin(authentication)) {
            result.put("name", config.getName());
            result.put("description", config.getDescription());
            result.put("priority", config.getPriority());
            result.put("trigger", config.getTrigger());
            result.put("riskScoreThreshold", config.getRiskScoreThreshold());
            result.put("slaMinutes", config.getSlaMinutes());
            result.put("requiresReview", config.isRequiresReview());
            result.put("assignRole", config.getAssignRole());
            result.put("version", config.getVersion());
            result.put("hasUnpublishedChanges", hasUnpublished(config, published));
            result.put("draft", draftResponse(config));
            result.put("published", published == null ? null : publishedResponse(published));
            result.put("createdBy", config.getCreatedBy());
            result.put("updatedBy", config.getUpdatedBy());
            result.put("createdAt", config.getCreatedAt());
            result.put("updatedAt", config.getUpdatedAt());
        } else if (published != null) {
            WorkOrderConfigSnapshot snapshot = published.toSnapshot();
            result.put("name", snapshot.getName());
            result.put("description", snapshot.getDescription());
            result.put("priority", snapshot.getPriority());
            result.put("trigger", snapshot.getTrigger());
            result.put("riskScoreThreshold", snapshot.getRiskScoreThreshold());
            result.put("slaMinutes", snapshot.getSlaMinutes());
            result.put("requiresReview", snapshot.isRequiresReview());
            result.put("assignRole", snapshot.getAssignRole());
            result.put("published", publishedResponse(published));
        }
        return result;
    }

    private boolean hasUnpublished(WorkOrderConfig config, WorkOrderConfigVersion published) {
        return published != null && published.getSourceVersion() != config.getVersion();
    }

    private Map<String, Object> draftResponse(WorkOrderConfig config) {
        Map<String, Object> draft = new LinkedHashMap<String, Object>();
        draft.put("name", config.getName());
        draft.put("description", config.getDescription());
        draft.put("priority", config.getPriority());
        draft.put("trigger", config.getTrigger());
        draft.put("riskScoreThreshold", config.getRiskScoreThreshold());
        draft.put("slaMinutes", config.getSlaMinutes());
        draft.put("requiresReview", config.isRequiresReview());
        draft.put("assignRole", config.getAssignRole());
        draft.put("version", config.getVersion());
        return draft;
    }

    private Map<String, Object> versionResponse(WorkOrderConfigVersion version) {
        Map<String, Object> result = publishedResponse(version);
        result.put("configId", version.getConfigId());
        result.put("id", version.getId());
        result.put("sourceVersion", version.getSourceVersion());
        return result;
    }

    private Map<String, Object> publishedResponse(WorkOrderConfigVersion version) {
        WorkOrderConfigSnapshot snapshot = version.toSnapshot();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("versionNo", snapshot.getConfigVersion());
        result.put("code", snapshot.getCode());
        result.put("name", snapshot.getName());
        result.put("description", snapshot.getDescription());
        result.put("priority", snapshot.getPriority());
        result.put("trigger", snapshot.getTrigger());
        result.put("riskScoreThreshold", snapshot.getRiskScoreThreshold());
        result.put("riskThresholdSemantics", THRESHOLD_SEMANTICS);
        result.put("slaMinutes", snapshot.getSlaMinutes());
        result.put("requiresReview", snapshot.isRequiresReview());
        result.put("assignRole", snapshot.getAssignRole());
        result.put("publishedBy", version.getPublishedBy());
        result.put("publishedAt", version.getPublishedAt());
        return result;
    }

    private WorkOrderConfig requireConfig(long id) {
        return store.findConfig(id).orElseThrow(WorkOrderConfigService::configNotFound);
    }

    private WorkOrderConfig requireConfigForUpdate(long id) {
        return store.findConfigForUpdate(id).orElseThrow(WorkOrderConfigService::configNotFound);
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private String actor(Authentication authentication) {
        return authentication == null ? "anonymous" : authentication.getName();
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private Map<String, Object> detail(Object... entries) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            detail.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return detail;
    }

    private void audit(Authentication authentication, String action, long targetId,
                       HttpServletRequest request, Map<String, Object> detail) {
        auditService.recordActor(actor(authentication), auditService.roleName(authentication), action,
                "WORK_ORDER_CONFIG", String.valueOf(targetId), "SUCCESS",
                auditService.requestId(request), detail);
    }

    public static ApiException configNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "WORK_ORDER_CONFIG_NOT_FOUND", "工单配置不存在");
    }

    public static ApiException configNotPublished() {
        return new ApiException(HttpStatus.CONFLICT, "CONFIG_NOT_PUBLISHED",
                "配置尚未发布，不能用于预览或建单");
    }

    public static ApiException configDisabled() {
        return new ApiException(HttpStatus.CONFLICT, "CONFIG_DISABLED", "配置已停用");
    }

    private static ApiException versionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "WORK_ORDER_CONFIG_VERSION_CONFLICT",
                "配置版本已变化，请刷新后重试");
    }

    private static ApiException codeExists() {
        return new ApiException(HttpStatus.CONFLICT, "WORK_ORDER_CONFIG_CODE_EXISTS",
                "配置编码已存在");
    }
}
