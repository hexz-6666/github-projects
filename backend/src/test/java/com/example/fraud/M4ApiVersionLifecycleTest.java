package com.example.fraud;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M4ApiVersionLifecycleTest {
    @AfterEach
    void resetExtensionState() {
        M4DashboardMysqlIT.resetApiVersionLifecycleForTest();
    }

    @Test
    void disabledConditionRestoresOriginalApiVersion() {
        String original = System.getProperty(M4ApiVersionLifecycle.KEY);
        System.setProperty(M4ApiVersionLifecycle.KEY, "1.40");
        try {
            ConditionEvaluationResult result = new M4DashboardMysqlIT.DockerGate(() -> false)
                    .evaluateExecutionCondition(null);
            assertTrue(result.isDisabled());
            assertEquals("1.40", System.getProperty(M4ApiVersionLifecycle.KEY));
        } finally {
            restore(original);
        }
    }

    @Test
    void enabledConditionAndAfterAllRestoreOriginalApiVersion() {
        String original = System.getProperty(M4ApiVersionLifecycle.KEY);
        System.setProperty(M4ApiVersionLifecycle.KEY, "1.40");
        try {
            ConditionEvaluationResult result = new M4DashboardMysqlIT.DockerGate(() -> true)
                    .evaluateExecutionCondition(null);
            assertFalse(result.isDisabled());
            assertEquals("1.41", System.getProperty(M4ApiVersionLifecycle.KEY));
            new M4DashboardMysqlIT.ApiVersionCleanup().afterAll(null);
            assertEquals("1.40", System.getProperty(M4ApiVersionLifecycle.KEY));
        } finally {
            restore(original);
        }
    }

    @Test
    void repeatedEnabledEvaluationDoesNotOverwriteOriginalApiVersion() {
        String original = System.getProperty(M4ApiVersionLifecycle.KEY);
        System.setProperty(M4ApiVersionLifecycle.KEY, "1.40");
        try {
            M4DashboardMysqlIT.DockerGate gate = new M4DashboardMysqlIT.DockerGate(() -> true);
            gate.evaluateExecutionCondition(null);
            gate.evaluateExecutionCondition(null);
            assertEquals("1.41", System.getProperty(M4ApiVersionLifecycle.KEY));
            new M4DashboardMysqlIT.ApiVersionCleanup().afterAll(null);
            assertEquals("1.40", System.getProperty(M4ApiVersionLifecycle.KEY));
        } finally {
            M4DashboardMysqlIT.resetApiVersionLifecycleForTest();
            restore(original);
        }
    }

    @Test
    void probeRuntimeExceptionDoesNotLeakApiVersion() {
        String original = System.getProperty(M4ApiVersionLifecycle.KEY);
        System.setProperty(M4ApiVersionLifecycle.KEY, "1.40");
        try {
            ConditionEvaluationResult result = new M4DashboardMysqlIT.DockerGate(() -> {
                throw new IllegalStateException("probe failed");
            }).evaluateExecutionCondition(null);
            assertTrue(result.isDisabled());
            assertEquals("1.40", System.getProperty(M4ApiVersionLifecycle.KEY));
        } finally {
            restore(original);
        }
    }

    @Test
    void probeErrorDoesNotLeakApiVersion() {
        String original = System.getProperty(M4ApiVersionLifecycle.KEY);
        System.setProperty(M4ApiVersionLifecycle.KEY, "1.40");
        try {
            M4DashboardMysqlIT.DockerGate gate = new M4DashboardMysqlIT.DockerGate(() -> {
                throw new AssertionError("probe failed");
            });
            assertThrows(AssertionError.class, () -> gate.evaluateExecutionCondition(null));
            assertEquals("1.40", System.getProperty(M4ApiVersionLifecycle.KEY));
        } finally {
            restore(original);
        }
    }

    @Test
    void helperDisabledPathRestoresOriginalApiVersion() {
        String original = System.getProperty(M4ApiVersionLifecycle.KEY);
        System.setProperty(M4ApiVersionLifecycle.KEY, "1.40");
        try {
            M4ApiVersionLifecycle.Outcome outcome =
                    M4ApiVersionLifecycle.evaluate(false, false);
            assertFalse(outcome.isEnabled());
            assertEquals("1.40", System.getProperty(M4ApiVersionLifecycle.KEY));
        } finally {
            restore(original);
        }
    }

    @Test
    void helperEnabledLifecycleRestoresOriginalApiVersion() {
        String original = System.getProperty(M4ApiVersionLifecycle.KEY);
        System.setProperty(M4ApiVersionLifecycle.KEY, "1.40");
        try {
            M4ApiVersionLifecycle.Outcome outcome =
                    M4ApiVersionLifecycle.evaluate(false, true);
            assertTrue(outcome.isEnabled());
            assertEquals("1.41", System.getProperty(M4ApiVersionLifecycle.KEY));
            M4ApiVersionLifecycle.restore(outcome.getPrevious());
            assertEquals("1.40", System.getProperty(M4ApiVersionLifecycle.KEY));
        } finally {
            restore(original);
        }
    }

    private void restore(String original) {
        if (original == null) {
            System.clearProperty(M4ApiVersionLifecycle.KEY);
        } else {
            System.setProperty(M4ApiVersionLifecycle.KEY, original);
        }
    }
}
