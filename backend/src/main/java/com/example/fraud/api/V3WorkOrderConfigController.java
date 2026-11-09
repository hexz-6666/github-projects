package com.example.fraud.api;

import com.example.fraud.domain.WorkOrderConfigStatus;
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

    public V3WorkOrderConfigController(WorkOrderConfigService service) {
        this.service = service;
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
        Map<String, Object> created = service.create(request, authentication, servletRequest);
        long id = ((Number) created.get("id")).longValue();
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v3/work-order-configs/" + id))
                .body(created);
    }

    @PutMapping(value = "/{id}", consumes = "application/json")
    public Map<String, Object> update(@PathVariable long id,
                                      @Valid @RequestBody WorkOrderConfigUpdateRequest request,
                                      Authentication authentication,
                                      HttpServletRequest servletRequest) {
        return service.update(id, request, authentication, servletRequest);
    }

    @PostMapping(value = "/{id}/publish", consumes = "application/json")
    public Map<String, Object> publish(@PathVariable long id,
                                       @Valid @RequestBody WorkOrderConfigExpectedVersionRequest request,
                                       Authentication authentication,
                                       HttpServletRequest servletRequest) {
        return service.publish(id, request.getExpectedVersion(), authentication, servletRequest);
    }

    @PostMapping(value = "/{id}/disable", consumes = "application/json")
    public Map<String, Object> disable(@PathVariable long id,
                                       @Valid @RequestBody WorkOrderConfigExpectedVersionRequest request,
                                       Authentication authentication,
                                       HttpServletRequest servletRequest) {
        return service.disable(id, request.getExpectedVersion(), authentication, servletRequest);
    }

    @PostMapping(value = "/preview", consumes = "application/json")
    public Map<String, Object> preview(@Valid @RequestBody WorkOrderConfigPreviewRequest request) {
        return service.preview(request.getConfigId());
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
