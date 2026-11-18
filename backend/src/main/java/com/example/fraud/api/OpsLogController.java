package com.example.fraud.api;

import com.example.fraud.service.OpsLogService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3/ops")
public class OpsLogController {
    private final OpsLogService service;

    public OpsLogController(OpsLogService service) {
        this.service = service;
    }

    @GetMapping("/logs")
    public Map<String, Object> logs(@RequestParam(required = false) String source,
                                    @RequestParam(required = false) String minLevel,
                                    @RequestParam(required = false) String taskType,
                                    @RequestParam(required = false) String taskId,
                                    @RequestParam(required = false) String requestId,
                                    @RequestParam(required = false) String relatedRequestId,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(required = false) String limit) {
        return service.query(source, minLevel, taskType, taskId, requestId, relatedRequestId,
                from, to, cursor, limit);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return service.health();
    }

    @PostMapping(value = "/client-errors", consumes = "application/json")
    public ResponseEntity<Void> clientError(@Valid @RequestBody OpsClientErrorRequest request,
                                            Authentication authentication,
                                            HttpServletRequest servletRequest) {
        service.recordClientError(request, authentication, servletRequest);
        return ResponseEntity.noContent().build();
    }
}
