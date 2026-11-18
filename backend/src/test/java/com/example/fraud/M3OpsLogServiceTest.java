package com.example.fraud;

import com.example.fraud.api.ApiException;
import com.example.fraud.api.OpsClientErrorRequest;
import com.example.fraud.config.RequestIdFilter;
import com.example.fraud.domain.OpsLogCode;
import com.example.fraud.domain.OpsLogEvent;
import com.example.fraud.domain.OpsLogHealth;
import com.example.fraud.domain.OpsLogLevel;
import com.example.fraud.domain.OpsLogSource;
import com.example.fraud.store.OpsLogStore;
import com.example.fraud.store.StubOpsLogStore;
import com.example.fraud.service.OpsLogService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M3OpsLogServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void stubStorageIsBoundedAndKeepsFixedServerSummary() {
        StubOpsLogStore store = new StubOpsLogStore(CLOCK, 5, 30);
        OpsLogHealth health = new OpsLogHealth();
        OpsLogService service = service(store, health);

        for (int i = 0; i < 8; i++) {
            service.record(OpsLogSource.BACKEND, OpsLogLevel.WARN,
                    OpsLogCode.HTTP_REQUEST_FAILED, null, null, null, "req-" + i,
                    null, "admin", null, null);
        }

        assertEquals(5, store.count());
        List<OpsLogEvent> events = store.query(new OpsLogStore.Query(null, "INFO", null,
                null, null, null, null, null, null, null, 10));
        assertTrue(events.size() <= 5);
        assertTrue(events.get(0).getSummary().equals("请求处理失败"));
    }

    @Test
    void afterCommitCallbackDoesNotEmitOnRollbackAndEmitsOnCommit() {
        StubOpsLogStore store = new StubOpsLogStore(CLOCK, 100, 30);
        OpsLogService service = service(store, new OpsLogHealth());
        OpsLogEvent event = event("req-commit");

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.recordAfterCommit(event);
            assertEquals(0, store.count());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertEquals(0, store.count());

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.recordAfterCommit(event);
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertEquals(1, store.count());
    }

    @Test
    void appendFailureIsSwallowedAndHealthDegrades() {
        OpsLogStore failing = new FailingStore();
        OpsLogHealth health = new OpsLogHealth();
        OpsLogService service = service(failing, health);
        service.probeStorage();

        assertDoesNotThrow(() -> service.record(OpsLogSource.TASK, OpsLogLevel.INFO,
                OpsLogCode.IMPORT_STARTED, null, "IMPORT_OLIST", null, "req-fail",
                null, "admin", null, null));
        assertEquals("DEGRADED", health.snapshot().get("status"));
        assertTrue(((Number) health.snapshot().get("writeFailureCount")).longValue() >= 1);
    }

    @Test
    void healthStartsDegradedAndFollowsDeterministicTransitions() {
        OpsLogHealth health = new OpsLogHealth();
        assertEquals("DEGRADED", health.snapshot().get("status"));
        assertEquals(Boolean.FALSE, health.snapshot().get("probed"));

        health.probe(true);
        assertEquals("UP", health.snapshot().get("status"));

        health.writeFailure();
        assertEquals("DEGRADED", health.snapshot().get("status"));
        assertEquals(1L, ((Number) health.snapshot().get("writeFailureCount")).longValue());

        health.writeSuccess();
        assertEquals("UP", health.snapshot().get("status"));

        health.probe(false);
        assertEquals("DEGRADED", health.snapshot().get("status"));
    }

    @Test
    void rateLimitKeepsTwentyPerMinuteForOneActor() {
        OpsLogService service = service(new StubOpsLogStore(CLOCK, 100, 30),
                new OpsLogHealth(), CLOCK, 2);
        Authentication auth = auth("rate-user");
        OpsClientErrorRequest request = clientError();
        for (int i = 0; i < 20; i++) {
            assertDoesNotThrow(() -> service.recordClientError(request, auth, request()));
        }
        ApiException exception = assertThrows(ApiException.class,
                () -> service.recordClientError(request, auth, request()));
        assertEquals(429, exception.getStatus().value());
        assertEquals("RATE_LIMITED", exception.getCode());
    }

    @Test
    void rateLimitCapacityRejectsNewWindowWithoutEvictingValidWindows() {
        MutableClock clock = new MutableClock(CLOCK.instant());
        OpsLogService service = service(new StubOpsLogStore(clock, 100, 30),
                new OpsLogHealth(), clock, 2);
        OpsClientErrorRequest request = clientError();

        assertDoesNotThrow(() -> service.recordClientError(request, auth("window-a"), request()));
        assertDoesNotThrow(() -> service.recordClientError(request, auth("window-b"), request()));
        assertDoesNotThrow(() -> service.recordClientError(request, auth("window-a"), request()));
        ApiException full = assertThrows(ApiException.class,
                () -> service.recordClientError(request, auth("window-c"), request()));
        assertEquals("RATE_LIMITED", full.getCode());
    }

    @Test
    void rateLimitCleansExpiredWindowsButRetainsActiveWindows() {
        MutableClock clock = new MutableClock(CLOCK.instant());
        OpsLogService service = service(new StubOpsLogStore(clock, 100, 30),
                new OpsLogHealth(), clock, 1);
        OpsClientErrorRequest request = clientError();

        assertDoesNotThrow(() -> service.recordClientError(request, auth("active"), request()));
        ApiException full = assertThrows(ApiException.class,
                () -> service.recordClientError(request, auth("blocked"), request()));
        assertEquals("RATE_LIMITED", full.getCode());

        clock.advance(Duration.ofSeconds(61));
        assertDoesNotThrow(() -> service.recordClientError(request, auth("blocked"), request()));
    }

    @Test
    void pruneUsesBoundedBatches() {
        StubOpsLogStore store = new StubOpsLogStore(CLOCK, 100, 30);
        OpsLogService service = service(store, new OpsLogHealth());
        for (int i = 0; i < 7; i++) {
            service.record(OpsLogSource.TASK, OpsLogLevel.INFO, OpsLogCode.IMPORT_COMPLETED,
                    null, "IMPORT_OLIST", "task-" + i, "req-" + i, null, "admin", null, null);
        }
        service.prune();
        assertTrue(store.count() <= 5);
    }

    private OpsLogService service(OpsLogStore store, OpsLogHealth health) {
        return service(store, health, CLOCK, 4096);
    }

    private OpsLogService service(OpsLogStore store, OpsLogHealth health, Clock clock,
                                  int clientWindowMax) {
        OpsLogService service = new OpsLogService(store, health, clock, true, 5, 30,
                2, 2, 20, clientWindowMax);
        service.probeStorage();
        return service;
    }

    private Authentication auth(String name) {
        return new UsernamePasswordAuthenticationToken(name, "n/a",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_REVIEWER")));
    }

    private OpsClientErrorRequest clientError() {
        OpsClientErrorRequest request = new OpsClientErrorRequest();
        request.setCode("CLIENT_HTTP_400");
        request.setRoute(OpsClientErrorRequest.Route.ORDERS);
        return request;
    }

    private HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, UUID.randomUUID().toString());
        return request;
    }

    private OpsLogEvent event(String requestId) {
        return new OpsLogEvent(CLOCK.instant(), OpsLogSource.TASK, OpsLogLevel.INFO,
                null, OpsLogCode.IMPORT_COMPLETED, "IMPORT_OLIST", "task", requestId,
                null, "admin", null, null);
    }

    private static class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static class FailingStore implements OpsLogStore {
        @Override
        public void append(OpsLogEvent event) {
            throw new IllegalStateException("test store unavailable");
        }

        @Override
        public List<OpsLogEvent> query(Query query) {
            throw new IllegalStateException("test store unavailable");
        }

        @Override
        public long count() {
            return 0;
        }

        @Override
        public int deleteOlderThan(Instant cutoff, int limit) {
            return 0;
        }

        @Override
        public int deleteOldest(int limit) {
            return 0;
        }

        @Override
        public boolean probe() {
            return true;
        }
    }
}
