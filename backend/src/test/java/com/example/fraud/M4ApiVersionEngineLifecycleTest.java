package com.example.fraud;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M4ApiVersionEngineLifecycleTest {
    private static final Object LOCK = M4ApiVersionLifecycle.TEST_LOCK;

    @AfterEach
    void reset() {
        M4DashboardMysqlIT.setDockerProbeForTest(null);
        M4DashboardMysqlIT.resetApiVersionLifecycleForTest();
    }

    @Test
    void classLevelDisabledSkipsMethodAndRestoresBeforeConditionReturns() {
        synchronized (LOCK) {
            withApiVersion("1.40", () -> {
                TestExecutionSummary summary = runFixture(DisabledFixture.class, () -> false);
                assertEquals(0, summary.getTestsStartedCount());
                assertEquals(0, summary.getTestsFailedCount());
                assertEquals("1.40", System.getProperty(M4ApiVersionLifecycle.KEY));
            });
        }
    }

    @Test
    void enabledFixtureRunsAndAfterAllRestores() {
        synchronized (LOCK) {
            withApiVersion("1.40", () -> {
                TestExecutionSummary summary = runFixture(EnabledFixture.class, () -> true);
                assertEquals(1, summary.getTestsSucceededCount());
                assertEquals("1.40", System.getProperty(M4ApiVersionLifecycle.KEY));
            });
        }
    }

    @Test
    void failedTestMethodStillRestoresInAfterAll() {
        synchronized (LOCK) {
            withApiVersion("1.40", () -> {
                TestExecutionSummary summary = runFixture(FailingFixture.class, () -> true);
                assertEquals(1, summary.getTestsFailedCount());
                assertEquals("1.40", System.getProperty(M4ApiVersionLifecycle.KEY));
            });
        }
    }

    @Test
    void beforeAllFailureBoundaryIsRecorded() {
        synchronized (LOCK) {
            withApiVersion("1.40", () -> {
                TestExecutionSummary summary = runFixture(BeforeAllFailureFixture.class, () -> true);
                assertTrue(summary.getTotalFailureCount() > 0);
                assertEquals("1.40", System.getProperty(M4ApiVersionLifecycle.KEY));
            });
        }
    }

    private TestExecutionSummary runFixture(Class<?> fixture, java.util.function.BooleanSupplier probe) {
        M4DashboardMysqlIT.setDockerProbeForTest(probe);
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(fixture))
                .build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(request, listener);
        return listener.getSummary();
    }

    private void withApiVersion(String value, Runnable action) {
        String original = System.getProperty(M4ApiVersionLifecycle.KEY);
        System.setProperty(M4ApiVersionLifecycle.KEY, value);
        try {
            action.run();
        } finally {
            M4DashboardMysqlIT.setDockerProbeForTest(null);
            M4DashboardMysqlIT.resetApiVersionLifecycleForTest();
            restore(original);
        }
    }

    private void restore(String value) {
        if (value == null) {
            System.clearProperty(M4ApiVersionLifecycle.KEY);
        } else {
            System.setProperty(M4ApiVersionLifecycle.KEY, value);
        }
    }

    @ExtendWith({M4DashboardMysqlIT.DockerGate.class, M4DashboardMysqlIT.ApiVersionCleanup.class})
    static class DisabledFixture {
        @Test
        void mustNotRun() {
            throw new AssertionError("disabled fixture test must not run");
        }
    }

    @ExtendWith({M4DashboardMysqlIT.DockerGate.class, M4DashboardMysqlIT.ApiVersionCleanup.class})
    static class EnabledFixture {
        @Test
        void runs() {
            assertFalse("1.41".isEmpty());
        }
    }

    @ExtendWith({M4DashboardMysqlIT.DockerGate.class, M4DashboardMysqlIT.ApiVersionCleanup.class})
    static class FailingFixture {
        @Test
        void fails() {
            throw new AssertionError("expected fixture failure");
        }
    }

    @ExtendWith({M4DashboardMysqlIT.DockerGate.class, M4DashboardMysqlIT.ApiVersionCleanup.class})
    static class BeforeAllFailureFixture {
        @BeforeAll
        static void failBeforeAll() {
            throw new IllegalStateException("expected beforeAll failure");
        }

        @Test
        void neverRuns() {
            throw new AssertionError("must not run");
        }
    }
}
