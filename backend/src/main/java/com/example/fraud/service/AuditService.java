package com.example.fraud.service;

import com.example.fraud.store.V2Store;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final V2Store store;
    private final Clock clock;

    public AuditService(V2Store store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public void record(Authentication authentication, String action, String targetType,
                       String targetId, String result, HttpServletRequest request, Map<String, Object> detail) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("actorId", authentication == null ? "anonymous" : authentication.getName());
        event.put("actorRole", role(authentication));
        event.put("action", action);
        event.put("targetType", targetType);
        event.put("targetId", targetId);
        event.put("result", result);
        event.put("requestId", request == null ? null
                : String.valueOf(request.getAttribute("com.example.fraud.config.RequestIdFilter.requestId")));
        event.put("detail", detail == null ? new LinkedHashMap<String, Object>() : detail);
        event.put("createdAt", clock.instant().toString());
        store.appendAudit(event);
    }

    public void recordActor(String actorId, String role, String action, String targetType,
                            String targetId, String result, Map<String, Object> detail) {
        recordActor(actorId, role, action, targetType, targetId, result, null, detail);
    }

    /** M2 显式 actor 审计：requestId 来自同一请求，角色规范化为 ANALYST/REVIEWER/ADMIN。 */
    public void recordActor(String actorId, String role, String action, String targetType,
                            String targetId, String result, String requestId, Map<String, Object> detail) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("actorId", actorId);
        event.put("actorRole", normalizeRole(role));
        event.put("action", action);
        event.put("targetType", targetType);
        event.put("targetId", targetId);
        event.put("result", result);
        event.put("requestId", requestId);
        event.put("detail", detail == null ? new LinkedHashMap<String, Object>() : detail);
        event.put("createdAt", clock.instant().toString());
        store.appendAudit(event);
    }

    public String normalizeRole(String role) {
        if (role == null) {
            return null;
        }
        if (role.contains("ADMIN")) {
            return "ADMIN";
        }
        if (role.contains("REVIEWER")) {
            return "REVIEWER";
        }
        if (role.contains("ANALYST")) {
            return "ANALYST";
        }
        return role;
    }

    public String roleName(Authentication authentication) {
        return normalizeRole(role(authentication));
    }

    public String requestId(HttpServletRequest request) {
        return request == null ? null
                : String.valueOf(request.getAttribute("com.example.fraud.config.RequestIdFilter.requestId"));
    }

    public String role(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(authority.getAuthority());
        }
        return builder.toString();
    }
}
