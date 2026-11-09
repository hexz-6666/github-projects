package com.example.fraud.api;

import com.example.fraud.domain.WorkOrderScope;
import com.example.fraud.domain.WorkOrderStatus;
import com.example.fraud.service.WorkOrderService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3")
public class V3WorkOrderController {
    private final WorkOrderService service;

    public V3WorkOrderController(WorkOrderService service) {
        this.service = service;
    }

    @GetMapping("/work-orders")
    public PageResponse<Map<String, Object>> list(@RequestParam(required = false) String scope,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String page,
                                                   @RequestParam(required = false) String size,
                                                   Authentication authentication) {
        int number = RequestParameters.page(page);
        int pageSize = RequestParameters.size(size);
        return service.list(scope(scope), status(status), number, pageSize, authentication);
    }

    @GetMapping("/work-orders/assignees")
    public List<Map<String, Object>> assignees(Authentication authentication) {
        return service.assignees(authentication);
    }

    @GetMapping("/work-orders/{id}")
    public Map<String, Object> detail(@PathVariable long id, Authentication authentication) {
        return service.detail(id, authentication);
    }

    @GetMapping("/alerts/{alertId}/work-order-candidates")
    public Map<String, Object> candidates(@PathVariable long alertId, Authentication authentication) {
        return service.candidates(alertId, authentication);
    }

    @GetMapping("/alerts/{alertId}/work-orders")
    public List<Map<String, Object>> alertWorkOrders(@PathVariable long alertId,
                                                     Authentication authentication) {
        return service.alertWorkOrders(alertId, authentication);
    }

    @PostMapping(value = "/alerts/{alertId}/work-orders", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> create(@PathVariable long alertId,
                                                       @Valid @RequestBody WorkOrderCreateRequest request,
                                                       Authentication authentication,
                                                       HttpServletRequest servletRequest) {
        WorkOrderService.CreateResult result = service.create(alertId, request.getConfigId(),
                authentication, servletRequest);
        Map<String, Object> body = service.detail(result.getRecord(), authentication);
        body.put("idempotent", result.isIdempotent());
        long id = result.getRecord().getId();
        return ResponseEntity.status(result.isIdempotent() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/api/v3/work-orders/" + id))
                .body(body);
    }

    @PostMapping(value = "/work-orders/{id}/assign", consumes = "application/json")
    public Map<String, Object> assign(@PathVariable long id,
                                      @Valid @RequestBody WorkOrderAssignRequest request,
                                      Authentication authentication,
                                      HttpServletRequest servletRequest) {
        return service.assign(id, request.getAssigneeId(), request.getExpectedVersion(),
                authentication, servletRequest);
    }

    @PostMapping(value = "/work-orders/{id}/transitions", consumes = "application/json")
    public Map<String, Object> transition(@PathVariable long id,
                                          @Valid @RequestBody WorkOrderTransitionRequest request,
                                          Authentication authentication,
                                          HttpServletRequest servletRequest) {
        return service.transition(id, request.getTransition(), request.getExpectedVersion(),
                request.getNote(), request.getResolutionReviewId(), authentication, servletRequest);
    }

    @PostMapping(value = "/work-orders/{id}/notes", consumes = "application/json")
    public Map<String, Object> note(@PathVariable long id,
                                    @Valid @RequestBody WorkOrderNoteRequest request,
                                    Authentication authentication,
                                    HttpServletRequest servletRequest) {
        return service.addNote(id, request.getNote(), request.getExpectedVersion(),
                authentication, servletRequest);
    }

    private WorkOrderScope scope(String value) {
        if (value == null || value.trim().isEmpty()) {
            return WorkOrderScope.MINE;
        }
        try {
            return WorkOrderScope.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw ApiException.invalid("scope 必须是 mine、queue 或 all");
        }
    }

    private WorkOrderStatus status(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return WorkOrderStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw ApiException.invalid("status 不是有效工单状态");
        }
    }
}
