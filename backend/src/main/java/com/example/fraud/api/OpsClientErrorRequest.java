package com.example.fraud.api;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * The client error payload deliberately has no level, summary, actor, raw URL,
 * query string, stack or arbitrary detail fields. Level and summary are derived
 * server-side from the fixed code.
 */
public class OpsClientErrorRequest {
    @NotNull
    @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}")
    private String code;

    @NotNull
    private Route route;

    @Pattern(regexp = "^$|^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
            + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private String relatedRequestId;

    @Size(max = 32)
    @Pattern(regexp = "^$|^[A-Za-z0-9._-]{1,32}$")
    private String buildVersion;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Route getRoute() { return route; }
    public void setRoute(Route route) { this.route = route; }
    public String getRelatedRequestId() { return relatedRequestId; }
    public void setRelatedRequestId(String relatedRequestId) { this.relatedRequestId = relatedRequestId; }
    public String getBuildVersion() { return buildVersion; }
    public void setBuildVersion(String buildVersion) { this.buildVersion = buildVersion; }

    public enum Route {
        ORDERS("/orders"),
        ALERTS("/alerts"),
        ALERT_DETAIL("/alerts/:id"),
        WORK_ORDERS("/workorders"),
        WORK_ORDER_DETAIL("/workorders/:id"),
        WORK_ORDER_CONFIGS("/work-order-configs"),
        QUALITY("/quality"),
        MODELS("/models"),
        HEALTH("/health"),
        LOGIN("/login");

        private final String template;

        Route(String template) {
            this.template = template;
        }

        public String getTemplate() {
            return template;
        }
    }
}
