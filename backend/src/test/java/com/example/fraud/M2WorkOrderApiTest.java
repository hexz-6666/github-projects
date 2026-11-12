package com.example.fraud;

import com.example.fraud.domain.WorkOrderRecord;
import com.example.fraud.domain.WorkOrderStatus;
import com.example.fraud.store.WorkOrderStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    @Autowired
    private WorkOrderStore workOrderStore;

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

    @Test
    void idempotentCreateSurvivesDisabledConfigAndResolvedAlert() throws Exception {
        long config = publishedConfig("M2_WO_IDEMPOTENT", "0.3", "[\"LOW\"]", false);
        long workOrderId = createWorkOrderAndId(2, config, "demo_reviewer", "REVIEWER");

        mvc.perform(post("/api/v3/work-order-configs/" + config + "/disable").with(csrf())
                        .with(user("demo_admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v3/alerts/2/work-orders").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + config + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workOrderId))
                .andExpect(jsonPath("$.idempotent").value(true));

        mvc.perform(post("/api/v1/alerts/2/reviews").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewResult\":\"CONFIRMED\",\"expectedVersion\":1}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v3/alerts/2/work-orders").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + config + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workOrderId))
                .andExpect(jsonPath("$.idempotent").value(true));
    }

    @Test
    void detailActionsRespectRoleAssigneeAndTerminalState() throws Exception {
        long config = publishedConfig("M2_WO_ACTIONS", "0.3", "[\"LOW\"]", false);
        MvcResult created = createWorkOrder(2, config, "demo_reviewer", "REVIEWER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actions.canClaim").value(true))
                .andExpect(jsonPath("$.actions.canAssignSelf").value(true))
                .andExpect(jsonPath("$.actions.canAssign").value(false))
                .andExpect(jsonPath("$.actions.canStart").value(false))
                .andExpect(jsonPath("$.actions.canResolve").value(false))
                .andExpect(jsonPath("$.actions.canCancel").value(false))
                .andExpect(jsonPath("$.actions.canAddNote").value(false))
                .andReturn();
        long workOrderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asLong();

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/transitions").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transition\":\"CLAIM\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions.canClaim").value(false))
                .andExpect(jsonPath("$.actions.canStart").value(true))
                .andExpect(jsonPath("$.actions.canAddNote").value(true))
                .andExpect(jsonPath("$.actions.canCancel").value(false));

        mvc.perform(get("/api/v3/work-orders/" + workOrderId)
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions.canAssign").value(true))
                .andExpect(jsonPath("$.actions.canStart").value(true))
                .andExpect(jsonPath("$.actions.canAddNote").value(true))
                .andExpect(jsonPath("$.actions.canCancel").value(true))
                .andExpect(jsonPath("$.actions.canClaim").value(false))
                .andExpect(jsonPath("$.actions.canAssignSelf").value(false));
    }

    @Test
    void alertWorkOrdersFindsOldRecordBeyondFirstHundred() throws Exception {
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        workOrderStore.insert(record(1L, old));
        for (int i = 0; i < 100; i++) {
            workOrderStore.insert(record(1000L + i, old.plusSeconds(60L + i)));
        }

        mvc.perform(get("/api/v3/alerts/1/work-orders")
                        .with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].alertId").value(1));
    }

    @Test
    void concurrentCreateReturnsOneCreatedAndOneIdempotent() throws Exception {
        long config = publishedConfig("M2_WO_CONCURRENT", "0.3", "[\"LOW\"]", false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<Future<Integer>>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return createWorkOrder(2, config, "demo_reviewer", "REVIEWER")
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<Integer>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "concurrent create pool must terminate");
        java.util.Collections.sort(statuses);
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.Arrays.asList(200, 201), statuses);
    }

    @Test
    void idempotentCreateDoesNotExposeWorkOrderAssignedToAnotherReviewer() throws Exception {
        long config = publishedConfig("M2_WO_VISIBLE", "0.7", "[\"HIGH\"]", false);
        long workOrderId = createWorkOrderAndId(1, config, "demo_reviewer", "REVIEWER");

        mvc.perform(post("/api/v3/work-orders/" + workOrderId + "/transitions").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transition\":\"CLAIM\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value("demo_reviewer"));

        // Another REVIEWER must not learn that this alert already has a work
        // order, nor receive its alert/config/event/review/assignee detail.
        mvc.perform(post("/api/v3/alerts/1/work-orders").with(csrf())
                        .with(user("other_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + config + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.actions").doesNotExist())
                .andExpect(jsonPath("$.alert").doesNotExist())
                .andExpect(jsonPath("$.configSnapshot").doesNotExist())
                .andExpect(jsonPath("$.events").doesNotExist())
                .andExpect(jsonPath("$.resolutionReviewCandidates").doesNotExist())
                .andExpect(jsonPath("$.resolutionReview").doesNotExist())
                .andExpect(jsonPath("$.assigneeId").doesNotExist())
                .andExpect(jsonPath("$.createdBy").doesNotExist())
                .andExpect(jsonPath("$.updatedBy").doesNotExist())
                .andExpect(jsonPath("$.dueAt").doesNotExist())
                .andExpect(jsonPath("$.resolvedAt").doesNotExist());

        long otherConfig = publishedConfig("M2_WO_VISIBLE_OTHER", "0.7", "[\"HIGH\"]", false);
        mvc.perform(post("/api/v3/alerts/1/work-orders").with(csrf())
                        .with(user("other_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + otherConfig + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));

        // The current handler keeps the existing 200 idempotent contract.
        mvc.perform(post("/api/v3/alerts/1/work-orders").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + config + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workOrderId))
                .andExpect(jsonPath("$.idempotent").value(true))
                .andExpect(jsonPath("$.configSnapshot").exists());

        // ADMIN remains globally visible.
        mvc.perform(post("/api/v3/alerts/1/work-orders").with(csrf())
                        .with(user("demo_admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + config + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workOrderId))
                .andExpect(jsonPath("$.idempotent").value(true));

        // A visible caller may still receive the explicit different-config 409.
        mvc.perform(post("/api/v3/alerts/1/work-orders").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + otherConfig + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_ALREADY_EXISTS_DIFFERENT_CONFIG"));
    }

    @Test
    void idempotentRetryHasSingleCreatedEventAndAudit() throws Exception {
        long config = publishedConfig("M2_WO_SINGLE", "0.7", "[\"HIGH\"]", false);
        long workOrderId = createWorkOrderAndId(1, config, "demo_reviewer", "REVIEWER");

        mvc.perform(post("/api/v3/alerts/1/work-orders").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + config + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotent").value(true));

        mvc.perform(get("/api/v3/work-orders/" + workOrderId)
                        .with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].eventType").value("CREATED"));

        mvc.perform(get("/api/v3/alerts/1/work-orders")
                        .with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(workOrderId));

        String audit = mvc.perform(get("/api/v2/audit").param("size", "100")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int createdAuditCount = 0;
        for (JsonNode item : objectMapper.readTree(audit).get("items")) {
            if ("WORK_ORDER_CREATED".equals(item.get("action").asText())
                    && String.valueOf(workOrderId).equals(item.get("targetId").asText())) {
                createdAuditCount += 1;
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, createdAuditCount);
    }

    @Test
    void reviewAndCreateConcurrencyKeepsOneConsistentOutcome() throws Exception {
        long config = publishedConfig("M2_WO_RACE_REVIEW", "0.7", "[\"HIGH\"]", false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<MvcResult> createFuture = pool.submit(() -> {
            start.await(5, TimeUnit.SECONDS);
            return createWorkOrder(1, config, "demo_reviewer", "REVIEWER").andReturn();
        });
        Future<MvcResult> reviewFuture = pool.submit(() -> {
            start.await(5, TimeUnit.SECONDS);
            return mvc.perform(post("/api/v1/alerts/1/reviews").with(csrf())
                            .with(user("demo_reviewer").roles("REVIEWER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reviewResult\":\"CONFIRMED\",\"comment\":\"并发复核\"," +
                                    "\"expectedVersion\":1}"))
                    .andReturn();
        });
        start.countDown();
        MvcResult createResult = createFuture.get(10, TimeUnit.SECONDS);
        MvcResult reviewResult = reviewFuture.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "concurrency pool must terminate");

        int createStatus = createResult.getResponse().getStatus();
        int reviewStatus = reviewResult.getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertEquals(201, reviewStatus);
        assertTrue(createStatus == 201 || createStatus == 409,
                "create must be 201 or 409, was " + createStatus);

        JsonNode createBody = objectMapper.readTree(createResult.getResponse().getContentAsString());
        if (createStatus == 409) {
            org.junit.jupiter.api.Assertions.assertEquals("ALERT_NOT_ACTIONABLE",
                    createBody.get("code").asText());
            mvc.perform(get("/api/v3/alerts/1/work-orders")
                            .with(user("demo_admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        } else {
            long workOrderId = createBody.get("id").asLong();
            JsonNode reviewBody = objectMapper.readTree(reviewResult.getResponse().getContentAsString());
            Instant workOrderCreatedAt = Instant.parse(createBody.get("createdAt").asText());
            Instant reviewCreatedAt = Instant.parse(reviewBody.get("reviewedAt").asText());
            org.junit.jupiter.api.Assertions.assertFalse(workOrderCreatedAt.isAfter(reviewCreatedAt),
                    "a resolved alert must not get a work order created after the review");
            mvc.perform(get("/api/v3/work-orders/" + workOrderId)
                            .with(user("demo_admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.events", hasSize(1)))
                    .andExpect(jsonPath("$.events[0].eventType").value("CREATED"));
        }
    }

    @Test
    void m2EndpointsRequireAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/v3/work-orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v3/work-order-configs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mvc.perform(post("/api/v3/work-order-configs")
                        .with(user("demo_admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/v3/alerts/1/work-orders")
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    private WorkOrderRecord record(long alertId, Instant createdAt) {
        Instant now = Instant.now();
        return new WorkOrderRecord(0L, alertId, 1L, 1L, 1, "{}", "REVIEWER", null,
                WorkOrderStatus.OPEN, false, 120, now.plusSeconds(7200L), null, null, null,
                1, "seed", "seed", createdAt, createdAt);
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
