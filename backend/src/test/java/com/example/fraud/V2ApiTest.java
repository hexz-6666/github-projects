package com.example.fraud;

import com.example.fraud.service.ModelAdminClient;
import com.example.fraud.store.V2Store;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.import-root=target/test-import")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class V2ApiTest {
    private static final Path IMPORT_ROOT = Paths.get("target", "test-import");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private V2Store store;

    @MockBean
    private ModelAdminClient modelAdminClient;

    @BeforeEach
    void prepareFixtures() throws Exception {
        Path olist = IMPORT_ROOT.resolve("olist");
        Files.createDirectories(olist);
        Files.write(olist.resolve("olist_orders_dataset.csv"), Arrays.asList(
                "order_id,customer_id,order_status,order_purchase_timestamp",
                "o-1,c-1,delivered,2018-01-01 10:00:00",
                "o-2,c-2,canceled,2018-01-02 11:00:00"), StandardCharsets.UTF_8);
        Files.write(olist.resolve("olist_order_payments_dataset.csv"), Arrays.asList(
                "order_id,payment_sequential,payment_type,payment_installments,payment_value",
                "o-1,1,credit_card,1,100.00",
                "o-2,1,voucher,1,50.00"), StandardCharsets.UTF_8);
        Files.write(olist.resolve("olist_customers_dataset.csv"), Arrays.asList(
                "customer_id,customer_unique_id",
                "c-1,u-1",
                "c-2,u-2"), StandardCharsets.UTF_8);

        Path ieee = IMPORT_ROOT.resolve("ieee-cis");
        Files.createDirectories(ieee);
        Files.write(ieee.resolve("train_transaction.csv"), Arrays.asList(
                "TransactionID,TransactionDT,TransactionAmt,isFraud,card1",
                "1,0,10.00,0,1000",
                "2,60,900.00,1,1001"), StandardCharsets.UTF_8);
        Path artifact = Paths.get("runtime", "models", "ieee-logreg-v1.json");
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, "{}".getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void cleanArtifact() throws Exception {
        Files.deleteIfExists(Paths.get("runtime", "models", "ieee-logreg-v1.json"));
    }

    @Test
    @WithAnonymousUser
    void businessApiRequiresLogin() throws Exception {
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v2/models")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "analyst", roles = "ANALYST")
    void analystCannotReviewOrImport() throws Exception {
        mvc.perform(post("/api/v1/alerts/1/reviews").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewResult\":\"INCONCLUSIVE\",\"expectedVersion\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v2/imports").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceSystem\":\"OLIST\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "reviewer", roles = "REVIEWER")
    void reviewUsesSessionIdentityAndWritesAudit() throws Exception {
        mvc.perform(post("/api/v1/alerts/1/reviews").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewerId\":\"spoofed-client\",\"reviewResult\":\"INCONCLUSIVE\"," +
                                "\"expectedVersion\":1}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/alerts/1"))
                .andExpect(jsonPath("$.reviews[0].reviewerId").value("reviewer"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void olistImportIsIdempotentAndProducesQualityChecks() throws Exception {
        mvc.perform(post("/api/v2/imports").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceSystem\":\"OLIST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.acceptedRows").value(2))
                .andExpect(jsonPath("$.idempotent").value(false));
        mvc.perform(post("/api/v2/imports").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceSystem\":\"OLIST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotent").value(true))
                .andExpect(jsonPath("$.acceptedRows").value(2));
        mvc.perform(get("/api/v2/data-quality/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks", hasSize(5)))
                .andExpect(jsonPath("$.checks[0].passed").value(true))
                .andExpect(content().string(not(containsString("secret"))));
        mvc.perform(get("/api/v1/orders").param("keyword", "OLIST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void missingImportDirectoryIsAControlledError() throws Exception {
        Files.deleteIfExists(IMPORT_ROOT.resolve("olist").resolve("olist_orders_dataset.csv"));
        mvc.perform(post("/api/v2/imports").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceSystem\":\"OLIST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_SOURCE_NOT_FOUND"))
                .andExpect(content().string(not(containsString("target"))));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void trainingRegistersModelAndEvaluation() throws Exception {
        when(modelAdminClient.train(any(), any())).thenReturn(trainedResponse());
        mvc.perform(post("/api/v2/models/train").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelVersion\":\"ieee-logreg-v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model.modelType").value("TRAINED_LOGREG"))
                .andExpect(jsonPath("$.evaluation.prAuc").value(0.42))
                .andExpect(jsonPath("$.labelSemantics").value("MODEL_SCORE_NOT_GROUND_TRUTH"));
        mvc.perform(get("/api/v2/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sourceSystem").value("IEEE_CIS"));
        mvc.perform(get("/api/v2/evaluations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void invalidModelResponseIsRejected() throws Exception {
        Map<String, Object> bad = trainedResponse();
        bad.put("modelType", "DEMO_RULE");
        when(modelAdminClient.train(any(), any())).thenReturn(bad);
        mvc.perform(post("/api/v2/models/train").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelVersion\":\"ieee-bad-v1\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("MODEL_INVALID_RESPONSE"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void auditIsAppendOnlyAndAdminOnly() throws Exception {
        mvc.perform(post("/api/v2/imports").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceSystem\":\"OLIST\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v2/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].action").value("IMPORT_RUN"));
    }

    private Map<String, Object> trainedResponse() {
        Map<String, Object> metrics = new LinkedHashMap<String, Object>();
        metrics.put("trainRows", 420);
        metrics.put("validRows", 90);
        metrics.put("testRows", 90);
        metrics.put("testPositives", 5);
        metrics.put("baseRate", 0.055);
        metrics.put("prAuc", 0.42);
        metrics.put("rocAuc", 0.81);
        metrics.put("recallAtFpr1", 0.30);
        metrics.put("recallAtFpr5", 0.55);
        metrics.put("precisionAt100", 0.20);
        metrics.put("brier", 0.04);
        metrics.put("threshold", 0.5);
        Map<String, Object> confusion = new LinkedHashMap<String, Object>();
        confusion.put("tp", 2);
        confusion.put("fp", 8);
        confusion.put("tn", 77);
        confusion.put("fn", 3);
        metrics.put("confusion", confusion);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("modelVersion", "ieee-logreg-v1");
        response.put("modelType", "TRAINED_LOGREG");
        response.put("sourceSystem", "IEEE_CIS");
        response.put("artifactPath", "runtime/models/ieee-logreg-v1.json");
        response.put("featureSchema", Arrays.asList("TransactionAmt", "card1"));
        response.put("trainedAt", "2026-11-07T10:00:00Z");
        response.put("trainingRows", 420);
        response.put("positiveRate", 0.05);
        response.put("metrics", metrics);
        return response;
    }
}
