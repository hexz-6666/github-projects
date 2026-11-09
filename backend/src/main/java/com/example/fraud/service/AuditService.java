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
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("actorId", actorId);
        event.put("actorRole", role);
        event.put("action", action);
        event.put("targetType", targetType);
        event.put("targetId", targetId);
        event.put("result", result);
        event.put("requestId", null);
        event.put("detail", detail == null ? new LinkedHashMap<String, Object>() : detail);
        event.put("createdAt", clock.instant().toString());
        store.appendAudit(event);
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
