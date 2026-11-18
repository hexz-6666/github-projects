package com.example.fraud.api;

import com.example.fraud.service.AuditService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final AuditService auditService;

    public AuthController(AuthenticationManager authenticationManager, AuditService auditService) {
        this.authenticationManager = authenticationManager;
        this.auditService = auditService;
    }

    @GetMapping("/csrf")
    public Map<String, Object> csrf(CsrfToken token) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("headerName", token.getHeaderName());
        body.put("parameterName", token.getParameterName());
        body.put("token", token.getToken());
        return body;
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest request,
                            HttpServletRequest servletRequest,
                            HttpServletResponse servletResponse) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        } catch (AuthenticationException exception) {
            auditService.recordActor(request.getUsername(), null, "LOGIN", "USER",
                    request.getUsername(), "FAILED", null);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "用户名或密码错误");
        }
        servletRequest.getSession(true);
        servletRequest.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, servletRequest, servletResponse);
        auditService.record(authentication, "LOGIN", "USER", authentication.getName(), "SUCCESS",
                servletRequest, null);
        return toMe(authentication);
    }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        return toMe(authentication);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    private MeResponse toMe(Authentication authentication) {
        List<String> roles = new ArrayList<String>();
        if (authentication != null) {
            for (GrantedAuthority authority : authentication.getAuthorities()) {
                String value = authority.getAuthority();
                roles.add(value.startsWith("ROLE_") ? value.substring(5) : value);
            }
        }
        return new MeResponse(authentication == null ? null : authentication.getName(),
                roles, authentication != null && authentication.isAuthenticated());
    }
}
