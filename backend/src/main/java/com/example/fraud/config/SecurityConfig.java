package com.example.fraud.config;

import com.example.fraud.config.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.demo.analyst-password:Analyst-Demo-2026!}")
    private String analystPassword;
    @Value("${app.demo.reviewer-password:Reviewer-Demo-2026!}")
    private String reviewerPassword;
    @Value("${app.demo.admin-password:Admin-Demo-2026!}")
    private String adminPassword;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        UserDetails analyst = User.withUsername("demo_analyst")
                .password(encoder.encode(analystPassword))
                .roles("ANALYST")
                .build();
        UserDetails reviewer = User.withUsername("demo_reviewer")
                .password(encoder.encode(reviewerPassword))
                .roles("REVIEWER")
                .build();
        UserDetails admin = User.withUsername("demo_admin")
                .password(encoder.encode(adminPassword))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(analyst, reviewer, admin);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        http
            .csrf()
                .csrfTokenRepository(csrf)
                .ignoringAntMatchers("/api/v2/auth/login")
            .and()
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation().migrateSession()
            .and()
            .authorizeRequests()
                .antMatchers("/api/v1/health", "/api/v1/model/health",
                        "/api/v2/auth/login", "/api/v2/auth/csrf", "/error").permitAll()
                .antMatchers("/api/v2/imports/**").hasRole("ADMIN")
                .antMatchers("/api/v2/models/train").hasRole("ADMIN")
                .antMatchers("/api/v2/audit/**").hasRole("ADMIN")
                .antMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/v1/alerts/*/reviews").hasAnyRole("REVIEWER", "ADMIN")
                .anyRequest().authenticated()
            .and()
            .exceptionHandling()
                .authenticationEntryPoint((request, response, exception) ->
                        writeError(response, objectMapper, request, HttpStatus.UNAUTHORIZED,
                                "UNAUTHENTICATED", "需要登录后访问"))
                .accessDeniedHandler(accessDeniedHandler(objectMapper))
            .and()
            .formLogin().disable()
            .httpBasic().disable()
            .logout().disable();
        return http.build();
    }

    private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, exception) -> {
            if (exception instanceof CsrfException) {
                writeError(response, objectMapper, request, HttpStatus.FORBIDDEN,
                        "CSRF_INVALID", "CSRF 校验失败，请刷新页面后重试");
            } else {
                writeError(response, objectMapper, request, HttpStatus.FORBIDDEN,
                        "FORBIDDEN", "当前账号无权执行该操作");
            }
        };
    }

    private void writeError(HttpServletResponse response, ObjectMapper objectMapper,
                            HttpServletRequest request, HttpStatus status, String code, String message)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("timestamp", java.time.Instant.now().toString());
        body.put("status", status.value());
        body.put("code", code);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("requestId", String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
