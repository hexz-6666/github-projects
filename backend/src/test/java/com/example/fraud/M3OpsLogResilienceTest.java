package com.example.fraud;

import com.example.fraud.domain.OpsLogEvent;
import com.example.fraud.store.OpsLogStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class M3OpsLogResilienceTest {
    @Autowired
    private MockMvc mvc;

    @MockBean
    private OpsLogStore store;

    @Test
    @WithMockUser(username = "demo_admin", roles = "ADMIN")
    void failedLogAppendDoesNotChangeBusinessSuccess() throws Exception {
        Mockito.doThrow(new IllegalStateException("log storage unavailable"))
                .when(store).append(any(OpsLogEvent.class));

        mvc.perform(post("/api/v3/work-order-configs").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"M3_OPS_TEST\",\"name\":\"M3 日志韧性配置\"," +
                                "\"priority\":3,\"trigger\":{\"sourceSystems\":[\"SYNTHETIC\"]," +
                                "\"riskLevels\":[\"HIGH\"],\"alertStatuses\":[\"OPEN\"]," +
                                "\"reasonCodes\":[]},\"riskScoreThreshold\":0.7," +
                                "\"slaMinutes\":120,\"requiresReview\":false," +
                                "\"assignRole\":\"REVIEWER\"}"))
                .andExpect(status().isCreated());
        verify(store, Mockito.atLeastOnce()).append(any(OpsLogEvent.class));
    }

    @Test
    @WithMockUser(username = "demo_admin", roles = "ADMIN")
    void opsQueryFailureDoesNotRecursivelyAppend() throws Exception {
        Mockito.when(store.query(any(OpsLogStore.Query.class)))
                .thenThrow(new IllegalStateException("query unavailable"));

        mvc.perform(get("/api/v3/ops/logs")).andExpect(status().isInternalServerError());
        verify(store, never()).append(any(OpsLogEvent.class));
    }
}
