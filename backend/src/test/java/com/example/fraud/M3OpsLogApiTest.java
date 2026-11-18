package com.example.fraud;

import com.example.fraud.domain.OpsLogCode;
import com.example.fraud.domain.OpsLogEvent;
import com.example.fraud.domain.OpsLogLevel;
import com.example.fraud.domain.OpsLogSource;
import com.example.fraud.store.OpsLogStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class M3OpsLogApiTest {
    private static final String RELATED = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OpsLogStore store;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser(username = "demo_admin", roles = "ADMIN")
    void adminCanReadLogsAndHealthButReviewerCannot() throws Exception {
        mvc.perform(get("/api/v3/ops/logs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.minLevel").value("WARN"))
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/v3/ops/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.processLocal").value(true))
                .andExpect(jsonPath("$.countersResetOnRestart").value(true));

        mvc.perform(get("/api/v3/ops/logs").with(user("reviewer").roles("REVIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v3/ops/health").with(user("reviewer").roles("REVIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v3/ops/logs").with(anonymous())).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "demo_admin", roles = "ADMIN")
    void minLevelFiltersAndCursorAreConsistent() throws Exception {
        Instant now = Instant.parse("2026-11-12T07:00:00Z");
        append(OpsLogCode.IMPORT_STARTED, OpsLogSource.TASK, now.minusSeconds(30),
                "IMPORT_OLIST", "task-a", null, "req-a", null, "demo_admin");
        append(OpsLogCode.HTTP_REQUEST_FAILED, OpsLogSource.BACKEND, now.minusSeconds(20),
                null, "request-b", null, "req-b", null, "demo_admin");
        append(OpsLogCode.CLIENT_HTTP_5XX, OpsLogSource.FRONTEND, now.minusSeconds(10),
                null, "client-c", RELATED, "req-c", "dev", "demo_reviewer");

        mvc.perform(get("/api/v3/ops/logs").param("minLevel", "WARN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].code").value("CLIENT_HTTP_5XX"))
                .andExpect(jsonPath("$.items[1].code").value("HTTP_REQUEST_FAILED"));
        mvc.perform(get("/api/v3/ops/logs").param("minLevel", "INFO")
                        .param("source", "TASK").param("taskId", "task-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].code").value("IMPORT_STARTED"));
        mvc.perform(get("/api/v3/ops/logs").param("relatedRequestId", RELATED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].relatedRequestId").value(RELATED));

        MvcResult first = mvc.perform(get("/api/v3/ops/logs").param("minLevel", "INFO")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn();
        String cursor = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("nextCursor").asText();
        mvc.perform(get("/api/v3/ops/logs").param("minLevel", "INFO")
                        .param("limit", "2").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/v3/ops/logs").param("minLevel", "DEBUG"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v3/ops/logs").param("cursor", "not-base64"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v3/ops/logs").param("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v3/ops/logs").param("from", now.toString())
                        .param("to", now.plusSeconds(31L * 24 * 3600).toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "demo_admin", roles = "ADMIN")
    void configValidationAndPublishFailuresDoNotLeaveCompletedEvents() throws Exception {
        mvc.perform(post("/api/v3/work-order-configs").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"M3_LOG_INVALID\",\"name\":\"无效配置\"," +
                                "\"priority\":3,\"trigger\":{\"sourceSystems\":[\"SYNTHETIC\"]," +
                                "\"riskLevels\":[\"BOGUS\"],\"alertStatuses\":[\"OPEN\"]," +
                                "\"reasonCodes\":[]},\"riskScoreThreshold\":0.7," +
                                "\"slaMinutes\":120,\"requiresReview\":false," +
                                "\"assignRole\":\"REVIEWER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v3/ops/logs").param("source", "TASK")
                        .param("taskType", "WORK_ORDER_CONFIG").param("minLevel", "WARN"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "WORK_ORDER_CONFIG_VALIDATION_FAILED")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("WORK_ORDER_CONFIG_CREATED"))));

        MvcResult created = mvc.perform(post("/api/v3/work-order-configs").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"M3_LOG_CONFIG\",\"name\":\"日志配置\"," +
                                "\"priority\":3,\"trigger\":{\"sourceSystems\":[\"SYNTHETIC\"]," +
                                "\"riskLevels\":[\"HIGH\"],\"alertStatuses\":[\"OPEN\"]," +
                                "\"reasonCodes\":[]},\"riskScoreThreshold\":0.7," +
                                "\"slaMinutes\":120,\"requiresReview\":false," +
                                "\"assignRole\":\"REVIEWER\"}"))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/v3/work-order-configs/" + id + "/publish").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":999}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v3/ops/logs").param("taskId", String.valueOf(id))
                        .param("minLevel", "INFO"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("WORK_ORDER_CONFIG_PUBLISHED"))));

        mvc.perform(post("/api/v3/work-order-configs/" + id + "/publish").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v3/ops/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.writeFailureCount").value(0));
        mvc.perform(get("/api/v3/ops/logs").param("taskId", String.valueOf(id))
                        .param("minLevel", "INFO"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "WORK_ORDER_CONFIG_PUBLISHED")));
    }

    @Test
    @WithMockUser(username = "demo_reviewer", roles = "REVIEWER")
    void clientErrorMapsLevelAndKeepsRequestIdsSeparate() throws Exception {
        mvc.perform(post("/api/v3/ops/client-errors").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CLIENT_HTTP_5XX\",\"route\":\"WORK_ORDERS\"," +
                                "\"relatedRequestId\":\"" + RELATED + "\",\"buildVersion\":\"test\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v3/ops/logs").with(user("demo_admin").roles("ADMIN"))
                        .param("relatedRequestId", RELATED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].source").value("FRONTEND"))
                .andExpect(jsonPath("$.items[0].level").value("ERROR"))
                .andExpect(jsonPath("$.items[0].code").value("CLIENT_HTTP_5XX"))
                .andExpect(jsonPath("$.items[0].summary").value("客户端观察到服务端错误（5xx）"))
                .andExpect(jsonPath("$.items[0].route").value("/workorders"))
                .andExpect(jsonPath("$.items[0].relatedRequestId").value(RELATED))
                .andExpect(jsonPath("$.items[0].requestId").isString())
                .andExpect(jsonPath("$.items[0].actorId").value("demo_reviewer"));
    }

    @Test
    @WithMockUser(username = "demo_reviewer", roles = "REVIEWER")
    void clientErrorRejectsFreeTextAndNonClientCodes() throws Exception {
        mvc.perform(post("/api/v3/ops/client-errors").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CLIENT_HTTP_400\",\"route\":\"ORDERS\"," +
                                "\"summary\":\"secret password\",\"level\":\"ERROR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret password"))));
        mvc.perform(post("/api/v3/ops/client-errors").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"HTTP_REQUEST_FAILED\",\"route\":\"ORDERS\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v3/ops/client-errors").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CLIENT_HTTP_400\",\"route\":\"ARBITRARY\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v3/ops/client-errors").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CLIENT_HTTP_400\",\"route\":\"ORDERS\"," +
                                "\"relatedRequestId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v3/ops/client-errors").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CLIENT_HTTP_400\",\"route\":\"ORDERS\"," +
                                "\"buildVersion\":\"123456789012345678901234567890123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "demo_admin", roles = "ADMIN")
    void clientErrorRequiresCsrfAndIsRateLimited() throws Exception {
        mvc.perform(post("/api/v3/ops/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CLIENT_HTTP_400\",\"route\":\"ORDERS\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        String body = "{\"code\":\"CLIENT_HTTP_400\",\"route\":\"ORDERS\"}";
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/api/v3/ops/client-errors").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(post("/api/v3/ops/client-errors").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    private void append(OpsLogCode code, OpsLogSource source, Instant at, String taskType,
                        String taskId, String related, String requestId, String build,
                        String actor) {
        store.append(new OpsLogEvent(at, source, code.getLevel(), null, code, taskType,
                taskId, requestId, related, actor, build, null));
    }
}
