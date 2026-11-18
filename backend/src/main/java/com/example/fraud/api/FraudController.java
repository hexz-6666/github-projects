package com.example.fraud.api;

import com.example.fraud.domain.AlertRecord;
import com.example.fraud.domain.AlertStatus;
import com.example.fraud.domain.OpsLogCode;
import com.example.fraud.domain.OpsLogSource;
import com.example.fraud.domain.OrderRecord;
import com.example.fraud.domain.OrderStatus;
import com.example.fraud.domain.ReviewResult;
import com.example.fraud.domain.ScoreRecord;
import com.example.fraud.domain.RiskLevel;
import com.example.fraud.service.AuditService;
import com.example.fraud.service.HealthService;
import com.example.fraud.service.ModelClient;
import com.example.fraud.service.OpsLogService;
import com.example.fraud.service.ScoreService;
import com.example.fraud.store.FraudStore;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/v1")
public class FraudController {
    private final FraudStore store;
    private final ModelClient modelClient;
    private final ScoreService scoreService;
    private final HealthService healthService;
    private final AuditService auditService;
    private final OpsLogService opsLogService;

    public FraudController(FraudStore store, ModelClient modelClient,
                           ScoreService scoreService, HealthService healthService,
                           AuditService auditService, OpsLogService opsLogService) {
        this.store = store;
        this.modelClient = modelClient;
        this.scoreService = scoreService;
        this.healthService = healthService;
        this.auditService = auditService;
        this.opsLogService = opsLogService;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        HealthResponse response = healthService.aggregateHealth();
        HttpStatus status = "UP".equals(response.getStatus()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/model/health")
    public ModelHealthResponse modelHealth() {
        return modelClient.health();
    }

    @GetMapping("/orders")
    public PageResponse<OrderRecord> orders(@RequestParam(required = false) String page,
                                            @RequestParam(required = false) String size,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String keyword) {
        int number = RequestParameters.page(page);
        int pageSize = RequestParameters.size(size);
        OrderStatus orderStatus = RequestParameters.orderStatus(status);
        String match = RequestParameters.keyword(keyword);
        List<OrderRecord> items = store.findOrders(orderStatus, match,
                RequestParameters.offset(number, pageSize), pageSize);
        return new PageResponse<OrderRecord>(items, number, pageSize,
                store.countOrders(orderStatus, match));
    }

    @GetMapping("/alerts")
    public PageResponse<AlertSummaryResponse> alerts(@RequestParam(required = false) String page,
                                                      @RequestParam(required = false) String size,
                                                      @RequestParam(required = false) String alertStatus,
                                                      @RequestParam(required = false) String riskLevel) {
        int number = RequestParameters.page(page);
        int pageSize = RequestParameters.size(size);
        AlertStatus statusValue = RequestParameters.alertStatus(alertStatus);
        RiskLevel risk = RequestParameters.riskLevel(riskLevel);
        List<AlertSummaryResponse> items = store.findAlerts(statusValue, risk,
                        RequestParameters.offset(number, pageSize), pageSize)
                .stream().map(AlertSummaryResponse::new).collect(Collectors.toList());
        return new PageResponse<AlertSummaryResponse>(items, number, pageSize,
                store.countAlerts(statusValue, risk));
    }

    @GetMapping("/alerts/{alertId}")
    public AlertDetailResponse alert(@PathVariable String alertId) {
        long id = RequestParameters.alertId(alertId);
        AlertRecord alert = store.findAlert(id).orElseThrow(ApiException::alertNotFound);
        OrderRecord order = store.findOrder(alert.getOrderId()).orElseThrow(ApiException::alertNotFound);
        return new AlertDetailResponse(alert, order, store.findReviews(id));
    }

    @PostMapping(value = "/alerts/{alertId}/reviews", consumes = "application/json")
    public ResponseEntity<ReviewResponse> review(@PathVariable String alertId,
                                                 @Valid @RequestBody ReviewRequest request,
                                                 Authentication authentication,
                                                 HttpServletRequest servletRequest) {
        long id = RequestParameters.alertId(alertId);
        ReviewResult result = RequestParameters.reviewResult(request.getReviewResult());
        // v2：身份来自服务端会话；请求体 reviewerId 仅为 v1 兼容保留，不能决定审计主体。
        String reviewerId = authentication == null ? null : authentication.getName();
        if (reviewerId == null || reviewerId.trim().isEmpty()) {
            throw ApiException.unauthenticated();
        }
        String comment = request.getComment() == null || request.getComment().trim().isEmpty()
                ? null : request.getComment().trim();
        ReviewResponse response = store.review(id, reviewerId, result,
                comment, request.getExpectedVersion());
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("reviewResult", result.name());
        detail.put("newStatus", response.getNewStatus().name());
        detail.put("version", response.getVersion());
        auditService.record(authentication, "REVIEW_SUBMIT", "ALERT", String.valueOf(id),
                "SUCCESS", servletRequest, detail);
        return ResponseEntity.created(URI.create("/api/v1/alerts/" + id)).body(response);
    }

    @PostMapping("/orders/{orderId}/score")
    public ScoreRecord score(@PathVariable String orderId,
                             Authentication authentication,
                             HttpServletRequest servletRequest,
                             @org.springframework.web.bind.annotation.RequestHeader(
                                     value = "Content-Type", required = false) String contentType) {
        if (contentType != null) {
            try {
                if (!MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType))) {
                    throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            "UNSUPPORTED_MEDIA_TYPE", "评分请求如带 Content-Type 必须为 JSON");
                }
            } catch (IllegalArgumentException exception) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "UNSUPPORTED_MEDIA_TYPE", "Content-Type 不受支持");
            }
        }
        if (!orderId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw ApiException.invalid("orderId 格式不符合接口约束");
        }
        record(OpsLogCode.MODEL_SCORE_STARTED, orderId, authentication, servletRequest);
        ScoreRecord saved;
        try {
            saved = scoreService.score(orderId);
        } catch (RuntimeException exception) {
            servletRequest.setAttribute(OpsLogService.TASK_EVENT_ATTRIBUTE, Boolean.TRUE);
            record(OpsLogCode.MODEL_SCORE_FAILED, orderId, authentication, servletRequest);
            throw exception;
        }
        // The score write has returned; a later audit/response failure must not
        // turn the completed score into a MODEL_SCORE_FAILED event.
        record(OpsLogCode.MODEL_SCORE_COMPLETED, orderId, authentication, servletRequest);
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("scoreId", saved.getScoreId());
        detail.put("modelVersion", saved.getModelVersion());
        detail.put("riskLevel", saved.getRiskLevel().name());
        auditService.record(authentication, "SCORE_RUN", "ORDER", orderId, "SUCCESS",
                servletRequest, detail);
        return saved;
    }

    private void record(OpsLogCode code, String taskId, Authentication authentication,
                        HttpServletRequest request) {
        opsLogService.record(OpsLogSource.MODEL, code.getLevel(), code, null, "MODEL_SCORE",
                taskId, opsLogService.requestId(request), null,
                authentication == null ? null : authentication.getName(), null, null);
    }
}
