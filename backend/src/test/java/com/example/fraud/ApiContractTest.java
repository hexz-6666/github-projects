package com.example.fraud;

import com.example.fraud.api.ApiException;
import com.example.fraud.api.HealthResponse;
import com.example.fraud.api.ModelHealthResponse;
import com.example.fraud.domain.FeatureRecord;
import com.example.fraud.domain.RiskLevel;
import com.example.fraud.domain.ScoreRecord;
import com.example.fraud.service.ModelClient;
import com.example.fraud.store.FraudStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@org.springframework.security.test.context.support.WithMockUser(
        username = "demo-reviewer-01", roles = {"REVIEWER", "ADMIN"})
class ApiContractTest {
    @Autowired
    private MockMvc mvc;

    @MockBean
    private ModelClient modelClient;

    @SpyBean
    private FraudStore store;

    @Test
    void stubHealthIsDegraded503AndBypassesDatabase() throws Exception {
        when(modelClient.health()).thenReturn(health());
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.service").value("fraud-api"))
                .andExpect(jsonPath("$.storageMode").value("stub"))
                .andExpect(jsonPath("$.checks.database.status").value("BYPASS"))
                .andExpect(jsonPath("$.checks.database.detail").value("IN_MEMORY_CONTRACT_STORE"))
                .andExpect(jsonPath("$.checks.model.status").value("UP"))
                .andExpect(jsonPath("$.checks.model.modelVersion").value("synthetic-rule-v1"))
                .andExpect(jsonPath("$.timestamp").isString());
    }

    @Test
    void modelFailureIsDownWithoutExceptionDetails() throws Exception {
        when(modelClient.health()).thenThrow(ApiException.modelUnavailable());
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.checks.model.status").value("DOWN"))
                .andExpect(jsonPath("$.checks.database.status").value("BYPASS"))
                .andExpect(content().string(not(containsString("MODEL_BASE_URL"))))
                .andExpect(content().string(not(containsString("Exception"))));
        mvc.perform(get("/api/v1/model/health"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"));
    }

    @Test
    void modelHealthReturnsCurrentModelMetadata() throws Exception {
        when(modelClient.health()).thenReturn(health());
        mvc.perform(get("/api/v1/model/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.modelVersion").value("synthetic-rule-v1"))
                .andExpect(jsonPath("$.modelType").value("DEMO_RULE"))
                .andExpect(jsonPath("$.threshold").value(0.7));
    }

    @Test
    void ordersAreFilteredAndPaginatedInContractOrder() throws Exception {
        mvc.perform(get("/api/v1/orders").param("status", "PAID").param("keyword", "DEMO-C-0002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].orderId").value("DEMO-O-0002"))
                .andExpect(jsonPath("$.items[0].amount").value("680.00"))
                .andExpect(jsonPath("$.items[0].customerEmailMasked").value("demo-****@example.invalid"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.totalPages").value(1));
        mvc.perform(get("/api/v1/orders").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].orderId").value("DEMO-O-0002"))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(3));
        mvc.perform(get("/api/v1/orders").param("page", "3").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void alertsCanBeFilteredAndPaginated() throws Exception {
        mvc.perform(get("/api/v1/alerts").param("alertStatus", "OPEN").param("riskLevel", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].alertId").value(1))
                .andExpect(jsonPath("$.items[0].scoreId").doesNotExist())
                .andExpect(jsonPath("$.items[0].reasonCodes", hasSize(4)));
        mvc.perform(get("/api/v1/alerts").param("alertStatus", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @Test
    void alertDetailIncludesOrderAndReviewsButMissingAlertIs404() throws Exception {
        mvc.perform(get("/api/v1/alerts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertId").value(1))
                .andExpect(jsonPath("$.scoreId").value(1))
                .andExpect(jsonPath("$.modelVersion").value("synthetic-rule-v1"))
                .andExpect(jsonPath("$.threshold").value(0.7))
                .andExpect(jsonPath("$.order.orderId").value("DEMO-O-0001"))
                .andExpect(jsonPath("$.reviews", hasSize(0)));
        mvc.perform(get("/api/v1/alerts/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ALERT_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/alerts/999"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void inconclusiveReviewAppendsAuditAndIncrementsVersion() throws Exception {
        review("INCONCLUSIVE", 1)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/alerts/1"))
                .andExpect(jsonPath("$.reviewId").value(1))
                .andExpect(jsonPath("$.previousStatus").value("OPEN"))
                .andExpect(jsonPath("$.newStatus").value("REVIEWING"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reviewResult").value("INCONCLUSIVE"));
        mvc.perform(get("/api/v1/alerts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertStatus").value("REVIEWING"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reviews", hasSize(1)))
                .andExpect(jsonPath("$.reviews[0].reviewerId").value("demo-reviewer-01"))
                .andExpect(jsonPath("$.reviews[0].previousStatus").value("OPEN"))
                .andExpect(jsonPath("$.reviews[0].newStatus").value("REVIEWING"));
    }

    @Test
    void staleVersionDoesNotAppendReview() throws Exception {
        review("INCONCLUSIVE", 1).andExpect(status().isCreated());
        review("CONFIRMED", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALERT_VERSION_CONFLICT"));
        mvc.perform(get("/api/v1/alerts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reviews", hasSize(1)));
    }

    @Test
    void resolvedAlertRejectsFurtherReview() throws Exception {
        review("CONFIRMED", 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.version").value(2));
        review("REJECTED", 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALERT_ALREADY_RESOLVED"));
        mvc.perform(get("/api/v1/alerts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.reviews", hasSize(1)));
    }

    @Test
    void rejectedReviewResolvesAlert() throws Exception {
        review("REJECTED", 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newStatus").value("RESOLVED"));
    }

    @Test
    void malformedInputsReturnUniformErrorsWithoutEcho() throws Exception {
        mvc.perform(get("/api/v1/orders").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(content().string(not(containsString("101"))));
        mvc.perform(get("/api/v1/orders").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("NOT_A_STATUS"))));
        mvc.perform(get("/api/v1/alerts/0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/alerts/not-an-id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mvc.perform(post("/api/v1/alerts/1/reviews").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewerId\":\"demo-reviewer-01\",\"reviewResult\":\"BAD\"," +
                                "\"comment\":\"secret-invalid-value\",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(content().string(not(containsString("secret-invalid-value"))));
        mvc.perform(post("/api/v1/alerts/1/reviews").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewerId\":\"demo-reviewer-01\",\"reviewResult\":\"CONFIRMED\"," +
                                "\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("expectedVersion"));
        mvc.perform(post("/api/v1/alerts/1/reviews").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewerId\":\"demo-reviewer-01\",\"reviewResult\":\"CONFIRMED\"," +
                                "\"expectedVersion\":1,\"unknown\":\"do-not-echo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(content().string(not(containsString("do-not-echo"))));
        mvc.perform(post("/api/v1/alerts/1/reviews").with(csrf()).contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(get("/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void scoreSavesValidatedModelResultAsNewHistory() throws Exception {
        when(modelClient.score(any())).thenReturn(new ScoreRecord(0, "DEMO-O-0002",
                new BigDecimal("0.0500"), RiskLevel.LOW, "synthetic-rule-v1", "DEMO_RULE",
                new BigDecimal("0.7000"), Collections.<String>emptyList(),
                Instant.parse("2026-11-07T08:02:00Z")));
        mvc.perform(post("/api/v1/orders/DEMO-O-0002/score").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoreId").value(2))
                .andExpect(jsonPath("$.orderId").value("DEMO-O-0002"))
                .andExpect(jsonPath("$.score").value(0.05))
                .andExpect(jsonPath("$.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.modelVersion").value("synthetic-rule-v1"))
                .andExpect(jsonPath("$.scoredAt").value("2026-11-07T08:02:00Z"));
        mvc.perform(post("/api/v1/orders/DEMO-O-0002/score").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoreId").value(3));
    }

    @Test
    void modelFailureReturns502AndDoesNotSaveScore() throws Exception {
        when(modelClient.score(any())).thenThrow(ApiException.modelUnavailable());
        mvc.perform(post("/api/v1/orders/DEMO-O-0002/score").with(csrf()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"));
        verify(store, never()).saveScore(any(FeatureRecord.class), any(ScoreRecord.class));
    }

    @Test
    void unknownOrderAndNonJsonScoreContentAreRejected() throws Exception {
        mvc.perform(post("/api/v1/orders/UNKNOWN-O-9999/score").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        verify(modelClient, never()).score(any());
        mvc.perform(post("/api/v1/orders/DEMO-O-0002/score").with(csrf()).contentType(MediaType.TEXT_PLAIN))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/v1/orders/-bad/score").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    private org.springframework.test.web.servlet.ResultActions review(String result, int version) throws Exception {
        return mvc.perform(post("/api/v1/alerts/1/reviews").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewerId\":\"demo-reviewer-01\",\"reviewResult\":\"" + result + "\"," +
                        "\"comment\":\"synthetic review\",\"expectedVersion\":" + version + "}"));
    }

    private ModelHealthResponse health() {
        ModelHealthResponse health = new ModelHealthResponse();
        health.setStatus("UP");
        health.setService("fraud-model");
        health.setModelVersion("synthetic-rule-v1");
        health.setModelType("DEMO_RULE");
        health.setThreshold(new BigDecimal("0.7000"));
        health.setLabelSemantics("RISK_SCORE_NOT_GROUND_TRUTH");
        return health;
    }
}
