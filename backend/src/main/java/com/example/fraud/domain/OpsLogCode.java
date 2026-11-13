package com.example.fraud.domain;

/**
 * Fixed operational event codes. The client may only submit the codes marked
 * {@code clientAllowed}; levels and summaries are always server-owned.
 */
public enum OpsLogCode {
    CLIENT_HTTP_400(OpsLogLevel.WARN, "客户端请求参数无效（400）", true),
    CLIENT_HTTP_403(OpsLogLevel.WARN, "客户端请求被拒绝（403）", true),
    CLIENT_HTTP_404(OpsLogLevel.WARN, "客户端请求资源不存在（404）", true),
    CLIENT_HTTP_409(OpsLogLevel.WARN, "客户端请求发生冲突（409）", true),
    CLIENT_HTTP_422(OpsLogLevel.WARN, "客户端请求参数无效（422）", true),
    CLIENT_HTTP_429(OpsLogLevel.WARN, "客户端请求被限流（429）", true),
    CLIENT_HTTP_5XX(OpsLogLevel.ERROR, "客户端观察到服务端错误（5xx）", true),
    CLIENT_NETWORK_ERROR(OpsLogLevel.ERROR, "客户端无法连接服务", true),
    CLIENT_RESPONSE_INVALID(OpsLogLevel.WARN, "客户端收到无法解析的响应", true),

    HTTP_REQUEST_FAILED(OpsLogLevel.WARN, "请求处理失败", false),
    IMPORT_STARTED(OpsLogLevel.INFO, "导入任务已开始", false),
    IMPORT_COMPLETED(OpsLogLevel.INFO, "导入任务已完成", false),
    IMPORT_FAILED(OpsLogLevel.ERROR, "导入任务失败", false),
    MODEL_SCORE_STARTED(OpsLogLevel.INFO, "模型评分已开始", false),
    MODEL_SCORE_COMPLETED(OpsLogLevel.INFO, "模型评分已完成", false),
    MODEL_SCORE_FAILED(OpsLogLevel.ERROR, "模型评分失败", false),
    MODEL_TRAIN_STARTED(OpsLogLevel.INFO, "模型训练已开始", false),
    MODEL_TRAIN_COMPLETED(OpsLogLevel.INFO, "模型训练已完成", false),
    MODEL_TRAIN_FAILED(OpsLogLevel.ERROR, "模型训练失败", false),
    WORK_ORDER_CREATE_STARTED(OpsLogLevel.INFO, "工单创建已开始", false),
    WORK_ORDER_CREATED(OpsLogLevel.INFO, "工单已创建", false),
    WORK_ORDER_CREATE_IDEMPOTENT(OpsLogLevel.INFO, "工单创建请求已幂等返回", false),
    WORK_ORDER_CREATE_REJECTED(OpsLogLevel.WARN, "工单创建被拒绝", false),
    WORK_ORDER_ASSIGN_STARTED(OpsLogLevel.INFO, "工单分派已开始", false),
    WORK_ORDER_ASSIGN_COMPLETED(OpsLogLevel.INFO, "工单分派已完成", false),
    WORK_ORDER_ASSIGN_REJECTED(OpsLogLevel.WARN, "工单分派被拒绝", false),
    WORK_ORDER_TRANSITION_STARTED(OpsLogLevel.INFO, "工单状态流转已开始", false),
    WORK_ORDER_TRANSITION_COMPLETED(OpsLogLevel.INFO, "工单状态流转已完成", false),
    WORK_ORDER_TRANSITION_REJECTED(OpsLogLevel.WARN, "工单状态流转被拒绝", false),
    WORK_ORDER_NOTE_STARTED(OpsLogLevel.INFO, "工单备注写入已开始", false),
    WORK_ORDER_NOTE_COMPLETED(OpsLogLevel.INFO, "工单备注已写入", false),
    WORK_ORDER_NOTE_REJECTED(OpsLogLevel.WARN, "工单备注写入被拒绝", false),
    WORK_ORDER_CONFIG_CREATE_STARTED(OpsLogLevel.INFO, "工单配置创建已开始", false),
    WORK_ORDER_CONFIG_CREATED(OpsLogLevel.INFO, "工单配置已创建", false),
    WORK_ORDER_CONFIG_CREATE_REJECTED(OpsLogLevel.WARN, "工单配置创建被拒绝", false),
    WORK_ORDER_CONFIG_UPDATE_STARTED(OpsLogLevel.INFO, "工单配置更新已开始", false),
    WORK_ORDER_CONFIG_UPDATED(OpsLogLevel.INFO, "工单配置已更新", false),
    WORK_ORDER_CONFIG_UPDATE_REJECTED(OpsLogLevel.WARN, "工单配置更新被拒绝", false),
    WORK_ORDER_CONFIG_PUBLISH_STARTED(OpsLogLevel.INFO, "工单配置发布已开始", false),
    WORK_ORDER_CONFIG_PUBLISHED(OpsLogLevel.INFO, "工单配置已发布", false),
    WORK_ORDER_CONFIG_PUBLISH_FAILED(OpsLogLevel.WARN, "工单配置发布失败", false),
    WORK_ORDER_CONFIG_DISABLE_STARTED(OpsLogLevel.INFO, "工单配置停用已开始", false),
    WORK_ORDER_CONFIG_DISABLED(OpsLogLevel.INFO, "工单配置已停用", false),
    WORK_ORDER_CONFIG_DISABLE_FAILED(OpsLogLevel.WARN, "工单配置停用失败", false),
    WORK_ORDER_CONFIG_VALIDATION_FAILED(OpsLogLevel.WARN, "工单配置校验失败", false),
    WORK_ORDER_CONFIG_VERSION_CONFLICT(OpsLogLevel.WARN, "工单配置版本冲突", false),
    WORK_ORDER_CONFIG_NOT_FOUND(OpsLogLevel.WARN, "工单配置不存在", false);

    private final OpsLogLevel level;
    private final String summary;
    private final boolean clientAllowed;

    OpsLogCode(OpsLogLevel level, String summary, boolean clientAllowed) {
        this.level = level;
        this.summary = summary;
        this.clientAllowed = clientAllowed;
    }

    public OpsLogLevel getLevel() {
        return level;
    }

    public String getSummary() {
        return summary;
    }

    public boolean isClientAllowed() {
        return clientAllowed;
    }
}
