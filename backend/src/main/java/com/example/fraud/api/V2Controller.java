package com.example.fraud.api;

import com.example.fraud.domain.OpsLogCode;
import com.example.fraud.domain.OpsLogSource;
import com.example.fraud.service.ImportService;
import com.example.fraud.service.ModelAdminService;
import com.example.fraud.service.OpsLogService;
import com.example.fraud.store.V2Store;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
public class V2Controller {
    private final V2Store store;
    private final ImportService importService;
    private final ModelAdminService modelAdminService;
    private final OpsLogService opsLogService;

    public V2Controller(V2Store store, ImportService importService, ModelAdminService modelAdminService,
                        OpsLogService opsLogService) {
        this.store = store;
        this.importService = importService;
        this.modelAdminService = modelAdminService;
        this.opsLogService = opsLogService;
    }

    @PostMapping(value = "/imports", consumes = "application/json")
    public Map<String, Object> importData(@Valid @RequestBody ImportRequest request,
                                          Authentication authentication,
                                          HttpServletRequest servletRequest) {
        String source = request.getSourceSystem() == null ? ""
                : request.getSourceSystem().trim().toUpperCase();
        String taskType = "IEEE_CIS".equals(source) ? "IMPORT_IEEE_CIS" : "IMPORT_OLIST";
        record(OpsLogCode.IMPORT_STARTED, taskType, null, authentication, servletRequest);
        try {
            Map<String, Object> result = importService.run(request.getSourceSystem(), authentication,
                    servletRequest);
            OpsLogCode completion = "FAILED".equals(result.get("status"))
                    ? OpsLogCode.IMPORT_FAILED : OpsLogCode.IMPORT_COMPLETED;
            record(completion, taskType, String.valueOf(result.get("batchId")), authentication,
                    servletRequest);
            return result;
        } catch (RuntimeException exception) {
            markTaskFailure(servletRequest);
            record(OpsLogCode.IMPORT_FAILED, taskType, null, authentication, servletRequest);
            throw exception;
        }
    }

    @GetMapping("/imports")
    public PageResponse<Map<String, Object>> imports(@RequestParam(required = false) String page,
                                                      @RequestParam(required = false) String size) {
        int number = RequestParameters.page(page);
        int pageSize = RequestParameters.size(size);
        return new PageResponse<Map<String, Object>>(
                store.findImports(RequestParameters.offset(number, pageSize), pageSize),
                number, pageSize, store.countImports());
    }

    @GetMapping("/imports/{batchId}")
    public Map<String, Object> importBatch(@PathVariable String batchId) {
        validateBatchId(batchId);
        return store.findImport(batchId).orElseThrow(ApiException::batchNotFound);
    }

    @GetMapping("/imports/{batchId}/quality")
    public Map<String, Object> quality(@PathVariable String batchId) {
        validateBatchId(batchId);
        Map<String, Object> batch = store.findImport(batchId).orElseThrow(ApiException::batchNotFound);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("batch", batch);
        result.put("checks", store.findDqChecks(batchId));
        return result;
    }

    @GetMapping("/data-quality/summary")
    public Map<String, Object> qualitySummary() {
        List<Map<String, Object>> latest = store.findImports(0, 1);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (latest.isEmpty()) {
            result.put("batch", null);
            result.put("checks", Collections.emptyList());
            result.put("message", "尚无导入批次；请先导入 Olist 或 IEEE-CIS 本地文件");
            return result;
        }
        Map<String, Object> batch = latest.get(0);
        result.put("batch", batch);
        result.put("checks", store.findDqChecks(String.valueOf(batch.get("batchId"))));
        return result;
    }

    @GetMapping("/audit")
    public PageResponse<Map<String, Object>> audit(@RequestParam(required = false) String page,
                                                    @RequestParam(required = false) String size) {
        int number = RequestParameters.page(page);
        int pageSize = RequestParameters.size(size);
        return new PageResponse<Map<String, Object>>(
                store.findAudit(RequestParameters.offset(number, pageSize), pageSize),
                number, pageSize, store.countAudit());
    }

    @GetMapping("/models")
    public List<Map<String, Object>> models() {
        return modelAdminService.models();
    }

    @PostMapping(value = "/models/train", consumes = "application/json")
    public Map<String, Object> train(@Valid @RequestBody TrainRequest request,
                                     Authentication authentication,
                                     HttpServletRequest servletRequest) {
        record(OpsLogSource.MODEL, OpsLogCode.MODEL_TRAIN_STARTED, "MODEL_TRAIN",
                request.getModelVersion(), authentication, servletRequest);
        try {
            Map<String, Object> result = modelAdminService.train(request.getModelVersion(),
                    authentication, servletRequest);
            record(OpsLogSource.MODEL, OpsLogCode.MODEL_TRAIN_COMPLETED, "MODEL_TRAIN",
                    request.getModelVersion(), authentication, servletRequest);
            return result;
        } catch (RuntimeException exception) {
            markTaskFailure(servletRequest);
            record(OpsLogSource.MODEL, OpsLogCode.MODEL_TRAIN_FAILED, "MODEL_TRAIN",
                    request.getModelVersion(), authentication, servletRequest);
            throw exception;
        }
    }

    @GetMapping("/evaluations")
    public List<Map<String, Object>> evaluations() {
        return modelAdminService.evaluations();
    }

    @GetMapping("/ieee/summary")
    public Map<String, Object> ieeeSummary() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("transactions", store.countIeeeTransactions());
        result.put("sourceSystem", "IEEE_CIS");
        result.put("labelSemantics", "IEEE_CIS_TRANSACTION_LABEL_ONLY");
        result.put("orderJoinAllowed", false);
        return result;
    }

    private void record(OpsLogCode code, String taskType, String taskId,
                        Authentication authentication, HttpServletRequest request) {
        record(OpsLogSource.TASK, code, taskType, taskId, authentication, request);
    }

    private void record(OpsLogSource source, OpsLogCode code, String taskType, String taskId,
                        Authentication authentication, HttpServletRequest request) {
        opsLogService.record(source, code.getLevel(), code, null, taskType, taskId,
                opsLogService.requestId(request), null,
                authentication == null ? null : authentication.getName(), null, null);
    }

    private void markTaskFailure(HttpServletRequest request) {
        request.setAttribute(OpsLogService.TASK_EVENT_ATTRIBUTE, Boolean.TRUE);
    }

    private void validateBatchId(String batchId) {
        if (batchId == null || !batchId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw ApiException.invalid("batchId 格式不合法");
        }
    }
}
