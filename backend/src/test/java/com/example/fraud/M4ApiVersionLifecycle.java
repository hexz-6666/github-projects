package com.example.fraud;

/** Test-only guard for the Docker Java API version property. */
final class M4ApiVersionLifecycle {
    static final String KEY = "api.version";
    static final String REQUIRED_VERSION = "1.41";
    static final Object TEST_LOCK = new Object();

    private M4ApiVersionLifecycle() {
    }

    static String begin() {
        String previous = System.getProperty(KEY);
        System.setProperty(KEY, REQUIRED_VERSION);
        return previous;
    }

    static Outcome decide(String previous, boolean required, boolean dockerAvailable) {
        if (!required && !dockerAvailable) {
            // The condition returns disabled and no afterAll callback will run.
            restore(previous);
            return new Outcome(false, previous);
        }
        return new Outcome(true, previous);
    }

    static Outcome evaluate(boolean required, boolean dockerAvailable) {
        return decide(begin(), required, dockerAvailable);
    }

    static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(KEY);
        } else {
            System.setProperty(KEY, previous);
        }
    }

    static final class Outcome {
        private final boolean enabled;
        private final String previous;

        private Outcome(boolean enabled, String previous) {
            this.enabled = enabled;
            this.previous = previous;
        }

        boolean isEnabled() {
            return enabled;
        }

        String getPrevious() {
            return previous;
        }
    }
}
