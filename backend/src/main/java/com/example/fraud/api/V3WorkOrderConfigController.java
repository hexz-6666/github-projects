package com.example.fraud.api;

import com.example.fraud.domain.OpsLogCode;
import com.example.fraud.domain.OpsLogSource;
import com.example.fraud.domain.WorkOrderConfigStatus;
import com.example.fraud.service.OpsLogService;
import com.example.fraud.service.WorkOrderConfigService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3/work-order-configs")
public class V3WorkOrderConfigController {
    private final WorkOrderConfigService service;
    private final OpsLogService opsLogService;

    public V3WorkOrderConfigController(WorkOrderConfigService service, OpsLogService opsLogService) {
        this.service = service;
        this.opsLogService = opsLogService;
    }

    @GetMapping
    public PageResponse<Map<String, Object>> list(@RequestParam(required = false) String page,
                                                   @RequestParam(required = false) String size,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String keyword,
                                                   Authentication authentication) {
        int number = RequestParameters.page(page);
        int pageSize = RequestParameters.size(size);
        return service.list(keyword, status(status), number, pageSize, authentication);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id, Authentication authentication) {
        return service.get(id, authentication);
    }

    @GetMapping("/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable long id, Authentication authentication) {
        return service.versions(id, authentication);
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody WorkOrderConfigCreateRequest request,
                                                       Authentication authentication,
                                                       HttpServletRequest servletRequest) {
        record(OpsLogCode.WORK_ORDER_CONFIG_CREATE_STARTED, null, authentication, servletRequest);
        try {
            Map<String, Object> created = service.create(request, authentication, servletRequest);
            long id = ((Number) created.get("id")).longValue();
            record(OpsLogCode.WORK_ORDER_CONFIG_CREATED, String.valueOf(id), authentication,
                    servletRequest);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .location(URI.create("/api/v3/work-order-configs/" + id))
                    .body(created);
        } catch (ApiException exception) {
            fail("CREATE", null, exception, authentication, servletRequest);
            throw exception;
        }
    }

    @PutMapping(value = "/{id}", consumes = "application/json")
    public Map<String, Object> update(@PathVariable long id,
                                      @Valid @RequestBody WorkOrderConfigUpdateRequest request,
                                      Authentication authentication,
                                      HttpServletRequest servletRequest) {
        record(OpsLogCode.WORK_ORDER_CONFIG_UPDATE_STARTED, String.valueOf(id), authentication,
                servletRequest);
        try {
            Map<String, Object> updated = service.update(id, request, authentication, servletRequest);
            record(OpsLogCode.WORK_ORDER_CONFIG_UPDATED, String.valueOf(id), authentication,
                    servletRequest);
            return updated;
        } catch (ApiException exception) {
            fail("UPDATE", String.valueOf(id), exception, authentication, servletRequest);
            throw exception;
        }
    }

    @PostMapping(value = "/{id}/publish", consumes = "application/json")
    public Map<String, Object> publish(@PathVariable long id,
                                       @Valid @RequestBody WorkOrderConfigExpectedVersionRequest request,
                                       Authentication authentication,
                                       HttpServletRequest servletRequest) {
        record(OpsLogCode.WORK_ORDER_CONFIG_PUBLISH_STARTED, String.valueOf(id), authentication,
                servletRequest);
        try {
            Map<String, Object> published = service.publish(id, request.getExpectedVersion(),
                    authentication, servletRequest);
            record(OpsLogCode.WORK_ORDER_CONFIG_PUBLISHED, String.valueOf(id), authentication,
                    servletRequest);
            return published;
        } catch (ApiException exception) {
            fail("PUBLISH", String.valueOf(id), exception, authentication, servletRequest);
            throw exception;
        }
    }

    @PostMapping(value = "/{id}/disable", consumes = "application/json")
    public Map<String, Object> disable(@PathVariable long id,
                                       @Valid @RequestBody WorkOrderConfigExpectedVersionRequest request,
                                       Authentication authentication,
                                       HttpServletRequest servletRequest) {
        record(OpsLogCode.WORK_ORDER_CONFIG_DISABLE_STARTED, String.valueOf(id), authentication,
                servletRequest);
        try {
            Map<String, Object> disabled = service.disable(id, request.getExpectedVersion(),
                    authentication, servletRequest);
            record(OpsLogCode.WORK_ORDER_CONFIG_DISABLED, String.valueOf(id), authentication,
                    servletRequest);
            return disabled;
        } catch (ApiException exception) {
            fail("DISABLE", String.valueOf(id), exception, authentication, servletRequest);
            throw exception;
        }
    }

    @PostMapping(value = "/preview", consumes = "application/json")
    public Map<String, Object> preview(@Valid @RequestBody WorkOrderConfigPreviewRequest request) {
        return service.preview(request.getConfigId());
    }

    private void record(OpsLogCode code, String taskId, Authentication authentication,
                        HttpServletRequest request) {
        opsLogService.record(OpsLogSource.TASK, code.getLevel(), code, null, "WORK_ORDER_CONFIG",
                taskId, opsLogService.requestId(request), null,
                authentication == null ? null : authentication.getName(), null, null);
    }

    private void fail(String operation, String taskId, ApiException exception,
                      Authentication authentication, HttpServletRequest request) {
        request.setAttribute(OpsLogService.TASK_EVENT_ATTRIBUTE, Boolean.TRUE);
        OpsLogCode code;
        if ("VALIDATION_ERROR".equals(exception.getCode())
                || "INVALID_PARAMETER".equals(exception.getCode())) {
            code = OpsLogCode.WORK_ORDER_CONFIG_VALIDATION_FAILED;
        } else if ("WORK_ORDER_CONFIG_VERSION_CONFLICT".equals(exception.getCode())) {
            code = OpsLogCode.WORK_ORDER_CONFIG_VERSION_CONFLICT;
        } else if ("PUBLISH".equals(operation)) {
            code = OpsLogCode.WORK_ORDER_CONFIG_PUBLISH_FAILED;
        } else if ("DISABLE".equals(operation)) {
            code = OpsLogCode.WORK_ORDER_CONFIG_DISABLE_FAILED;
        } else if ("UPDATE".equals(operation)) {
            code = OpsLogCode.WORK_ORDER_CONFIG_UPDATE_REJECTED;
        } else {
            code = OpsLogCode.WORK_ORDER_CONFIG_CREATE_REJECTED;
        }
        record(code, taskId, authentication, request);
    }

    private WorkOrderConfigStatus status(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return WorkOrderConfigStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw ApiException.invalid("status 必须是 DRAFT、ACTIVE 或 DISABLED");
        }
    }
}
