package com.example.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class M2ConfigApiTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser(username = "demo_admin", roles = "ADMIN")
    void configIsDraftUntilPublishedAndReaderCannotSeeDraftFields() throws Exception {
        long id = createConfig("M2_CONFIG_A", "0.7000", "[\"HIGH\"]", "[]", true);
        mvc.perform(get("/api/v3/work-order-configs").with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        mvc.perform(get("/api/v3/work-order-configs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.publishedVersion").doesNotExist())
                .andExpect(jsonPath("$.riskThresholdSemantics").value("风险分阈值（非欺诈概率）"))
                .andExpect(jsonPath("$.draft.version").value(1));

        mvc.perform(post("/api/v3/work-order-configs/" + id + "/publish").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/v3/work-order-configs").with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].draft").doesNotExist())
                .andExpect(jsonPath("$.items[0].version").doesNotExist())
                .andExpect(jsonPath("$.items[0].published.versionNo").value(1));

        mvc.perform(post("/api/v3/work-order-configs/preview").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + id + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMatched").value(1))
                .andExpect(jsonPath("$.groupsByRiskLevel[0].key").value("HIGH"))
                .andExpect(jsonPath("$.groupsByAlertStatus").doesNotExist());
    }

    @Test
    @WithMockUser(username = "demo_admin", roles = "ADMIN")
    void publishCreatesImmutableVersionsAndUnpublishedEditsAreNotUsed() throws Exception {
        long id = createConfig("M2_CONFIG_B", "0.7000", "[\"HIGH\"]", "[]", true);
        publish(id, 1);

        mvc.perform(put("/api/v3/work-order-configs/" + id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson("M2_CONFIG_B", "0.5000", "[\"HIGH\",\"LOW\"]",
                                "[]", true, 2).replace("\"expectedVersion\":2",
                                "\"expectedVersion\":2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.hasUnpublishedChanges").value(true))
                .andExpect(jsonPath("$.publishedVersion").value(1));

        // 预览仍使用已发布 v1（阈值 0.7），而不是草稿 v0.5。
        mvc.perform(post("/api/v3/work-order-configs/preview").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + id + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMatched").value(1))
                .andExpect(jsonPath("$.publishedVersion").value(1));

        mvc.perform(post("/api/v3/work-order-configs/" + id + "/publish").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(2))
                .andExpect(jsonPath("$.hasUnpublishedChanges").value(false));

        mvc.perform(get("/api/v3/work-order-configs/" + id + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("M2 配置"))
                .andExpect(jsonPath("$[1].name").value("M2 配置"));

        mvc.perform(post("/api/v3/work-order-configs/" + id + "/disable").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.version").value(5));

        mvc.perform(post("/api/v3/work-order-configs/preview").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + id + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_DISABLED"));

        mvc.perform(put("/api/v3/work-order-configs/" + id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson("M2_CONFIG_B", "0.5000", "[\"HIGH\"]", "[]", true, 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_DISABLED"));
    }

    @Test
    @WithMockUser(username = "demo_admin", roles = "ADMIN")
    void configWritesRequireAdminAndPreviewRequiresPublished() throws Exception {
        long id = createConfig("M2_CONFIG_C", "0.7000", "[\"HIGH\"]", "[]", true);
        mvc.perform(post("/api/v3/work-order-configs/preview").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":" + id + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_NOT_PUBLISHED"));

        mvc.perform(post("/api/v3/work-order-configs").with(csrf())
                        .with(user("demo_reviewer").roles("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson("M2_NOPE", "0.5", "[\"HIGH\"]", "[]", true, 1)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v3/work-order-configs").with(csrf())
                        .with(user("demo_analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson("M2_ANALYST", "0.5", "[\"HIGH\"]", "[]", true, 1)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v3/work-order-configs").with(csrf())
                        .with(user("demo_admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson("M2_CONFIG_C", "0.5", "[\"HIGH\"]", "[]", true, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_CONFIG_CODE_EXISTS"));
    }

    @Test
    @WithMockUser(username = "demo_admin", roles = "ADMIN")
    void triggerWhitelistAndUnknownFieldsAreRejected() throws Exception {
        mvc.perform(post("/api/v3/work-order-configs").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"M2_BAD\",\"name\":\"bad\",\"priority\":3," +
                                "\"trigger\":{\"sourceSystems\":[\"SYNTHETIC\"]," +
                                "\"riskLevels\":[\"HIGH\"],\"alertStatuses\":[],\"reasonCodes\":[]," +
                                "\"expression\":\"score > 0.5\"},\"riskScoreThreshold\":0.5," +
                                "\"slaMinutes\":60,\"requiresReview\":false,\"assignRole\":\"REVIEWER\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v3/work-order-configs").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"M2_BAD2\",\"name\":\"bad\",\"priority\":3," +
                                "\"trigger\":{\"sourceSystems\":[\"SYNTHETIC\",\"SYNTHETIC\"]," +
                                "\"riskLevels\":[\"HIGH\"],\"alertStatuses\":[],\"reasonCodes\":[]}," +
                                "\"riskScoreThreshold\":0.5,\"slaMinutes\":60," +
                                "\"requiresReview\":false,\"assignRole\":\"REVIEWER\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v3/work-order-configs").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"M2_BAD3\",\"name\":\"bad\",\"priority\":3," +
                                "\"trigger\":{\"sourceSystems\":[],\"riskLevels\":[]," +
                                "\"alertStatuses\":[],\"reasonCodes\":[]},\"riskScoreThreshold\":0.5," +
                                "\"slaMinutes\":60,\"requiresReview\":false,\"assignRole\":\"REVIEWER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(username = "demo_admin", roles = "ADMIN")
    void staleExpectedVersionDoesNotOverwriteConfig() throws Exception {
        long id = createConfig("M2_CONFIG_STALE", "0.7", "[\"HIGH\"]", "[]", false);
        publish(id, 1);
        mvc.perform(put("/api/v3/work-order-configs/" + id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson("M2_CONFIG_STALE", "0.6", "[\"HIGH\"]", "[]", false, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        mvc.perform(put("/api/v3/work-order-configs/" + id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson("M2_CONFIG_STALE", "0.4", "[\"HIGH\"]", "[]", false, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_CONFIG_VERSION_CONFLICT"));
        mvc.perform(get("/api/v3/work-order-configs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScoreThreshold").value(0.6));
    }

    @Test
    @WithMockUser(username = "demo_admin", roles = "ADMIN")
    void readerSearchUsesPublishedSnapshotAndCannotProbeDraftNames() throws Exception {
        long id = createConfig("M2_CONFIG_PRIV", "0.7", "[\"HIGH\"]", "[]", false);
        publish(id, 1);
        mvc.perform(put("/api/v3/work-order-configs/" + id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"DraftSecret\",\"priority\":3," +
                                "\"trigger\":{\"sourceSystems\":[\"SYNTHETIC\"]," +
                                "\"riskLevels\":[\"HIGH\"],\"alertStatuses\":[\"OPEN\"]," +
                                "\"reasonCodes\":[]},\"riskScoreThreshold\":0.7,\"slaMinutes\":120," +
                                "\"requiresReview\":false,\"assignRole\":\"REVIEWER\"," +
                                "\"expectedVersion\":2}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v3/work-order-configs").param("keyword", "DraftSecret")
                        .with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.page.totalElements").value(0));

        mvc.perform(get("/api/v3/work-order-configs").param("keyword", "M2 配置")
                        .with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("M2 配置"));

        mvc.perform(get("/api/v3/work-order-configs/" + id)
                        .with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("M2 配置"))
                .andExpect(jsonPath("$.draft").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());

        mvc.perform(get("/api/v3/work-order-configs").param("keyword", "DraftSecret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("DraftSecret"));
    }

    private long createConfig(String code, String threshold, String riskLevels,
                              String reasonCodes, boolean requiresReview) throws Exception {
        MvcResult result = mvc.perform(post("/api/v3/work-order-configs").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(code, threshold, riskLevels, reasonCodes, requiresReview, 0)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void publish(long id, int expectedVersion) throws Exception {
        mvc.perform(post("/api/v3/work-order-configs/" + id + "/publish").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + expectedVersion + "}"))
                .andExpect(status().isOk());
    }

    private String configJson(String code, String threshold, String riskLevels,
                              String reasonCodes, boolean requiresReview, int expectedVersion) {
        String json = "{";
        if (expectedVersion == 0) {
            json += "\"code\":\"" + code + "\",";
        }
        json += "\"name\":\"M2 配置\",\"description\":\"demo\"," +
                "\"priority\":3,\"trigger\":{\"sourceSystems\":[\"SYNTHETIC\"]," +
                "\"riskLevels\":" + riskLevels + ",\"alertStatuses\":[\"OPEN\"]," +
                "\"reasonCodes\":" + reasonCodes + "},\"riskScoreThreshold\":" + threshold + "," +
                "\"slaMinutes\":120,\"requiresReview\":" + requiresReview + "," +
                "\"assignRole\":\"REVIEWER\"";
        if (expectedVersion > 0) {
            json += ",\"expectedVersion\":" + expectedVersion;
        }
        return json + "}";
    }
}
