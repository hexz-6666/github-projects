package com.example.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class M2WorkOrderApiTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createIsExplicitIdempotentAndDifferentConfigIsConflict() throws Exception {
        long configA = publishedConfig("M2_WO_A", "0.7", "[\"HIGH\"]", true);
        MvcResult created = createWorkOrder(1, configA, "demo_reviewer", "REVIEWER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotent").value(false))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.alertId").value(1))
                .andExpect(jsonPath("$.configVersionNo").value(1))
                .andExpect(jsonPath("$.dueAt").isString())
                .andExpect(jsonPath("$.slaStatus").value("ON_TRACK"))
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andReturn();
        long workOrderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asLong();

        mvc.perform(post("/api/v3/alerts/1/work-orders").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + configA + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workOrderId))
                .andExpect(jsonPath("$.idempotent").value(true));

        long configB = publishedConfig("M2_WO_B", "0.7", "[\"HIGH\"]", true);
        mvc.perform(post("/api/v3/alerts/1/work-orders").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + configB + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_ALREADY_EXISTS_DIFFERENT_CONFIG"));

        mvc.perform(get("/api/v3/alerts/1/work-orders")
                        .with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mvc.perform(get("/api/v3/alerts/1/work-order-candidates")
                        .with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionable").value(true))
                .andExpect(jsonPath("$.existingWorkOrder.id").value(workOrderId))
                .andExpect(jsonPath("$.candidates", hasSize(2)));

        mvc.perform(get("/api/v3/work-orders").param("scope", "queue")
                        .with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));
        mvc.perform(get("/api/v3/work-orders").param("scope", "queue")
                        .with(user("demo_analyst").roles("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));
        mvc.perform(get("/api/v3/work-orders").param("scope", "all")
                        .with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v3/work-orders").param("scope", "all")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));

        mvc.perform(get("/api/v3/work-orders/assignees")
                        .with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v3/work-orders/assignees")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void workOrderCompletesOnlyAfterReviewAndNotes() throws Exception {
        long config = publishedConfig("M2_WO_REVIEW", "0.7", "[\"HIGH\"]", true);
        long workOrderId = createWorkOrderAndId(1, config, "demo_reviewer", "REVIEWER");

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/transitions").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transition\":\"CLAIM\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assigneeId").value("demo_reviewer"));

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/transitions").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transition\":\"START\",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/transitions").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transition\":\"RESOLVE\",\"expectedVersion\":3,\"note\":\"处理完成\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOLUTION_REVIEW_REQUIRED"));

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/notes").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"已联系客户\",\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.events", hasSize(4)));

        mvc.perform(post("/api/v1/alerts/1/reviews").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewResult\":\"CONFIRMED\",\"comment\":\"确认风险\"," +
                                "\"expectedVersion\":1}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/transitions").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transition\":\"RESOLVE\",\"expectedVersion\":4," +
                                "\"note\":\"已确认并完成\",\"resolutionReviewId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionReviewId").value(1))
                .andExpect(jsonPath("$.slaStatus").value("MET"))
                .andExpect(jsonPath("$.resolutionReview.reviewResult").value("CONFIRMED"))
                .andExpect(jsonPath("$.events", hasSize(5)));

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/notes").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"终态备注\",\"expectedVersion\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_TERMINAL"));

        String audit = mvc.perform(get("/api/v2/audit").param("size", "100")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode auditItems = objectMapper.readTree(audit).get("items");
        boolean resolvedAudit = false;
        for (JsonNode item : auditItems) {
            if ("WORK_ORDER_RESOLVED".equals(item.get("action").asText())
                    && String.valueOf(workOrderId).equals(item.get("targetId").asText())) {
                resolvedAudit = true;
                break;
            }
        }
        assertTrue(resolvedAudit, "resolved work order must have a WORK_ORDER_RESOLVED audit event");
    }

    @Test
    void dueAtUsesPublishedSnapshotNotUnpublishedDraftSla() throws Exception {
        long config = publishedConfig("M2_WO_SLA", "0.7", "[\"HIGH\"]", false);
        mvc.perform(put("/api/v3/work-order-configs/" + config).with(csrf())
                        .with(user("demo_admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"草稿 SLA 1 分钟\",\"priority\":3," +
                                "\"trigger\":{\"sourceSystems\":[\"SYNTHETIC\"]," +
                                "\"riskLevels\":[\"HIGH\"],\"alertStatuses\":[\"OPEN\"]," +
                                "\"reasonCodes\":[]},\"riskScoreThreshold\":0.7,\"slaMinutes\":1," +
                                "\"requiresReview\":false,\"assignRole\":\"REVIEWER\"," +
                                "\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasUnpublishedChanges").value(true));

        MvcResult created = createWorkOrder(1, config, "demo_reviewer", "REVIEWER")
                .andExpect(status().isCreated()).andReturn();
        JsonNode node = objectMapper.readTree(created.getResponse().getContentAsString());
        Instant createdAt = Instant.parse(node.get("createdAt").asText());
        Instant dueAt = Instant.parse(node.get("dueAt").asText());
        long minutes = Duration.between(createdAt, dueAt).toMinutes();
        assertTrue(minutes >= 119 && minutes <= 121,
                "dueAt must use published SLA 120, but was " + minutes + " minutes");
    }

    @Test
    void cancelRequiresAdminAndTerminalOrderIsReadOnly() throws Exception {
        long config = publishedConfig("M2_WO_CANCEL", "0.3", "[\"LOW\"]", false);
        long workOrderId = createWorkOrderAndId(2, config, "demo_reviewer", "REVIEWER");

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/transitions").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transition\":\"CANCEL\",\"expectedVersion\":1,\"note\":\"无法处理\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/transitions").with(csrf())
                        .with(user("demo_admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transition\":\"CANCEL\",\"expectedVersion\":1,\"note\":\"重复告警\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.slaStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isString());

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/transitions").with(csrf())
                        .with(user("demo_admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transition\":\"CANCEL\",\"expectedVersion\":2,\"note\":\"再次取消\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_TERMINAL"));

        mvc.perform(get("/api/v3/work-orders/" + workOrderId)
                        .with(user("demo_analyst").roles("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void adminCanAssignAndReassignWithAuditableRoleOverride() throws Exception {
        long config = publishedConfig("M2_WO_ASSIGN", "0.7", "[\"HIGH\"]", false);
        long workOrderId = createWorkOrderAndId(1, config, "demo_admin", "ADMIN");

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/assign").with(csrf())
                        .with(user("demo_admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"demo_reviewer\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assigneeId").value("demo_reviewer"));

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/assign").with(csrf())
                        .with(user("demo_admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"demo_admin\",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value("demo_admin"))
                .andExpect(jsonPath("$.events", hasSize(3)));

        mvc.perform(get("/api/v3/work-orders/" + workOrderId)
                        .with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isForbidden());

        String audit = mvc.perform(get("/api/v2/audit").param("size", "100")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        boolean overrideAudited = false;
        for (JsonNode item : objectMapper.readTree(audit).get("items")) {
            if ("WORK_ORDER_REASSIGNED".equals(item.get("action").asText())
                    && item.get("detail").get("roleOverride").asBoolean()) {
                overrideAudited = true;
                break;
            }
        }
        assertTrue(overrideAudited, "ADMIN reassignment across roles must audit roleOverride=true");
    }

    @Test
    void invalidTransitionsSpoofedFieldsAndAnalystWritesAreRejected() throws Exception {
        long config = publishedConfig("M2_WO_VALID", "0.7", "[\"HIGH\"]", false);
        long workOrderId = createWorkOrderAndId(1, config, "demo_admin", "ADMIN");

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/transitions").with(csrf())
                        .with(user("demo_admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transition\":\"RESOLVE\",\"expectedVersion\":1,\"note\":\"跳步\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_WORK_ORDER_TRANSITION"));

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/transitions").with(csrf())
                        .with(user("demo_analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transition\":\"CLAIM\",\"expectedVersion\":1}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/notes").with(csrf())
                        .with(user("demo_analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"不该写入\",\"expectedVersion\":1}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v3/alerts/1/work-orders").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + config + ",\"operatorId\":\"spoof\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private long createWorkOrderAndId(long alertId, long configId, String username, String role)
            throws Exception {
        MvcResult result = createWorkOrder(alertId, configId, username, role)
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions createWorkOrder(long alertId, long configId,
                                                                             String username, String role)
            throws Exception {
        return mvc.perform(post("/api/v3/alerts/" + alertId + "/work-orders").with(csrf())
                .with(user(username).roles(role))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"configId\":" + configId + "}"));
    }

    private long publishedConfig(String code, String threshold, String riskLevels,
                                 boolean requiresReview) throws Exception {
        MvcResult created = mvc.perform(post("/api/v3/work-order-configs").with(csrf())
                        .with(user("demo_admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"M2 工单配置\"," +
                                "\"priority\":3,\"trigger\":{\"sourceSystems\":[\"SYNTHETIC\"]," +
                                "\"riskLevels\":" + riskLevels + ",\"alertStatuses\":[\"OPEN\"]," +
                                "\"reasonCodes\":[]},\"riskScoreThreshold\":" + threshold + "," +
                                "\"slaMinutes\":120,\"requiresReview\":" + requiresReview + "," +
                                "\"assignRole\":\"REVIEWER\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode node = objectMapper.readTree(created.getResponse().getContentAsString());
        long id = node.get("id").asLong();
        mvc.perform(post("/api/v3/work-order-configs/" + id + "/publish").with(csrf())
                        .with(user("demo_admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        return id;
    }
}
