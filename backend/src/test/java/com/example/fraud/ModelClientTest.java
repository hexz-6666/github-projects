package com.example.fraud;

import com.example.fraud.api.ApiException;
import com.example.fraud.api.ModelHealthResponse;
import com.example.fraud.api.ModelScoreRequest;
import com.example.fraud.domain.FeatureRecord;
import com.example.fraud.domain.RiskLevel;
import com.example.fraud.domain.ScoreRecord;
import com.example.fraud.service.ModelClient;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

class ModelClientTest {
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private ModelClient client;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        restTemplate = new RestTemplate();
        restTemplate.getMessageConverters().stream()
                .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast)
                .forEach(converter -> converter.setObjectMapper(mapper));
        server = MockRestServiceServer.createServer(restTemplate);
        client = new ModelClient(restTemplate, "http://127.0.0.1:8000");
    }

    @Test
    void mapsRealHealthJsonIncludingLabelSemantics() {
        server.expect(requestTo("http://127.0.0.1:8000/health"))
                .andExpect(method(GET))
                .andRespond(withSuccess(healthJson(), MediaType.APPLICATION_JSON));
        ModelHealthResponse response = client.health();
        assertEquals("UP", response.getStatus());
        assertEquals("synthetic-rule-v1", response.getModelVersion());
        assertEquals("DEMO_RULE", response.getModelType());
        assertEquals(new BigDecimal("0.7"), response.getThreshold());
        assertEquals("RISK_SCORE_NOT_GROUND_TRUTH", response.getLabelSemantics());
        assertEquals(Boolean.TRUE, response.getReady());
        server.verify();
    }

    @Test
    void mapsRealScoreJsonAndSendsOnlyDwsFeatureFields() {
        server.expect(requestTo("http://127.0.0.1:8000/internal/v1/score"))
                .andExpect(method(POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.orderId").value("DEMO-O-0002"))
                .andExpect(jsonPath("$.amount").value(680.00))
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.paymentCount").value(1))
                .andExpect(jsonPath("$.accountAgeDays").value(94))
                .andExpect(jsonPath("$.transactionCount24h").value(1))
                .andExpect(jsonPath("$.featureId").doesNotExist())
                .andExpect(jsonPath("$.featureVersion").doesNotExist())
                .andRespond(withSuccess(scoreJson(), MediaType.APPLICATION_JSON));
        FeatureRecord feature = new FeatureRecord(2, "DEMO-O-0002", "synthetic-feature-v1",
                new BigDecimal("680.00"), "CNY", 1, 94, 1);
        ScoreRecord score = client.score(new ModelScoreRequest(feature));
        assertEquals("DEMO-O-0002", score.getOrderId());
        assertEquals(new BigDecimal("0.05"), score.getScore());
        assertEquals(RiskLevel.LOW, score.getRiskLevel());
        assertEquals("DEMO_RULE", score.getModelType());
        assertEquals("2026-11-07T08:02:00Z", score.getScoredAt().toString());
        server.verify();
    }

    @Test
    void sendsMaximumMysqlDecimalAsAnExactJsonNumber() throws Exception {
        server.expect(requestTo("http://127.0.0.1:8000/internal/v1/score"))
                .andExpect(method(POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"amount\":9999999999999999.99")))
                .andRespond(withSuccess(scoreJson().replace("\"score\":0.05", "\"score\":0.50")
                        .replace("\"riskLevel\":\"LOW\"", "\"riskLevel\":\"MEDIUM\"")
                        .replace("\"ruleCodes\":[]", "\"ruleCodes\":[\"HIGH_AMOUNT\"]"),
                        MediaType.APPLICATION_JSON));
        FeatureRecord feature = new FeatureRecord(2, "DEMO-O-0002", "synthetic-feature-v1",
                new BigDecimal("9999999999999999.99"), "CNY", 1, 94, 1);
        assertEquals(RiskLevel.MEDIUM, client.score(new ModelScoreRequest(feature)).getRiskLevel());
        server.verify();
    }

    @Test
    void health500BecomesModelUnavailableWithoutLeakingResponseBody() {
        server.expect(requestTo("http://127.0.0.1:8000/health"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"secret\":\"internal-model-detail\"}"));
        ApiException exception = assertThrows(ApiException.class, () -> client.health());
        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        assertEquals("MODEL_UNAVAILABLE", exception.getCode());
        org.junit.jupiter.api.Assertions.assertFalse(exception.getMessage().contains("internal-model-detail"));
        server.verify();
    }

    @Test
    void score500BecomesModelUnavailableWithoutLeakingResponseBody() {
        server.expect(requestTo("http://127.0.0.1:8000/internal/v1/score"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"secret\":\"internal-model-detail\"}"));
        ApiException exception = assertThrows(ApiException.class, () -> client.score(request()));
        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        assertEquals("MODEL_UNAVAILABLE", exception.getCode());
        org.junit.jupiter.api.Assertions.assertFalse(exception.getMessage().contains("internal-model-detail"));
        server.verify();
    }

    @Test
    void wrongOrderIdIsRejectedAsInvalidModelResponse() {
        server.expect(requestTo("http://127.0.0.1:8000/internal/v1/score"))
                .andRespond(withSuccess(scoreJson().replace("DEMO-O-0002", "DEMO-O-0003"),
                        MediaType.APPLICATION_JSON));
        assertInvalidScore();
    }

    @Test
    void wrongModelTypeIsRejectedAsInvalidModelResponse() {
        server.expect(requestTo("http://127.0.0.1:8000/internal/v1/score"))
                .andRespond(withSuccess(scoreJson().replace("DEMO_RULE", "TRAINED_PRODUCTION_MODEL"),
                        MediaType.APPLICATION_JSON));
        assertInvalidScore();
    }

    @Test
    void outOfRangeScoreAndThresholdAreRejected() {
        server.expect(requestTo("http://127.0.0.1:8000/internal/v1/score"))
                .andRespond(withSuccess(scoreJson().replace("\"score\":0.05", "\"score\":1.5"),
                        MediaType.APPLICATION_JSON));
        assertInvalidScore();
    }

    @Test
    void mismatchedRiskLevelIsRejected() {
        server.expect(requestTo("http://127.0.0.1:8000/internal/v1/score"))
                .andRespond(withSuccess(scoreJson().replace("\"riskLevel\":\"LOW\"",
                        "\"riskLevel\":\"HIGH\""), MediaType.APPLICATION_JSON));
        assertInvalidScore();
    }

    @Test
    void unknownScoreFieldIsRejectedInsteadOfSilentlyAccepted() {
        server.expect(requestTo("http://127.0.0.1:8000/internal/v1/score"))
                .andRespond(withSuccess(scoreJson().replace("\"orderId\":\"DEMO-O-0002\",",
                        "\"orderId\":\"DEMO-O-0002\",\"unknown\":\"ignored-value\","),
                        MediaType.APPLICATION_JSON));
        assertInvalidScore();
    }

    @Test
    void modelUrlMustBeALoopbackHttpOriginWithoutPathOrCredentials() {
        String[] invalid = {"http://example.com:8000", "http://127.0.0.1:8000/base",
                "http://127.0.0.1:8000?next=other", "http://user@127.0.0.1:8000",
                "file:///tmp/model", "http://[::1]:8000"};
        for (String url : invalid) {
            assertThrows(IllegalArgumentException.class, () -> new ModelClient(restTemplate, url));
        }
        new ModelClient(restTemplate, "http://localhost:8000");
        new ModelClient(restTemplate, "http://127.0.0.1:8000/");
    }

    private void assertInvalidScore() {
        ApiException exception = assertThrows(ApiException.class, () -> client.score(request()));
        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        assertEquals("MODEL_INVALID_RESPONSE", exception.getCode());
        server.verify();
    }

    private ModelScoreRequest request() {
        return new ModelScoreRequest(new FeatureRecord(2, "DEMO-O-0002", "synthetic-feature-v1",
                new BigDecimal("680.00"), "CNY", 1, 94, 1));
    }

    private String healthJson() {
        return "{\"status\":\"UP\",\"service\":\"fraud-model\"," +
                "\"modelVersion\":\"synthetic-rule-v1\",\"modelType\":\"DEMO_RULE\"," +
                "\"threshold\":0.7,\"ready\":true,\"labelSemantics\":\"RISK_SCORE_NOT_GROUND_TRUTH\"}";
    }

    private String scoreJson() {
        return "{\"orderId\":\"DEMO-O-0002\",\"score\":0.05,\"riskLevel\":\"LOW\"," +
                "\"modelVersion\":\"synthetic-rule-v1\",\"modelType\":\"DEMO_RULE\"," +
                "\"threshold\":0.7,\"ruleCodes\":[],\"scoredAt\":\"2026-11-07T08:02:00Z\"," +
                "\"labelSemantics\":\"RISK_SCORE_NOT_GROUND_TRUTH\"}";
    }
}
