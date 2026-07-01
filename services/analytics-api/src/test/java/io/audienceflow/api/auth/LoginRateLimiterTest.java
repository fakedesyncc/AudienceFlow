package io.audienceflow.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {
    private static final String EMAIL = "teacher@example.edu";
    private static final String IP = "203.0.113.7";

    private static final class MutableClock implements LoginRateLimiter.Clock {
        private Instant now = Instant.parse("2026-07-01T00:00:00Z");

        @Override
        public Instant now() {
            return now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    @Test
    void allowsUpToThresholdBeforeLocking() {
        LoginRateLimiter limiter = new LoginRateLimiter(new MutableClock());

        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS - 1; i++) {
            limiter.recordFailure(EMAIL, IP);
            assertThat(limiter.isLocked(EMAIL, IP)).isFalse();
        }

        limiter.recordFailure(EMAIL, IP);
        assertThat(limiter.isLocked(EMAIL, IP)).isTrue();
    }

    @Test
    void lockoutExpiresAfterWindow() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            limiter.recordFailure(EMAIL, IP);
        }
        assertThat(limiter.isLocked(EMAIL, IP)).isTrue();

        clock.advanceSeconds(LoginRateLimiter.WINDOW.getSeconds() + 1);
        assertThat(limiter.isLocked(EMAIL, IP)).isFalse();
    }

    @Test
    void successResetsFailureCount() {
        LoginRateLimiter limiter = new LoginRateLimiter(new MutableClock());

        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS - 1; i++) {
            limiter.recordFailure(EMAIL, IP);
        }
        assertThat(limiter.isLocked(EMAIL, IP)).isFalse();

        limiter.recordSuccess(EMAIL, IP);

        // After a reset the counter starts over; a single failure must not lock the account.
        limiter.recordFailure(EMAIL, IP);
        assertThat(limiter.isLocked(EMAIL, IP)).isFalse();
    }

    @Test
    void locksArePerEmailAndIp() {
        LoginRateLimiter limiter = new LoginRateLimiter(new MutableClock());

        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            limiter.recordFailure(EMAIL, IP);
        }

        assertThat(limiter.isLocked(EMAIL, IP)).isTrue();
        assertThat(limiter.isLocked(EMAIL, "198.51.100.9")).isFalse();
        assertThat(limiter.isLocked("other@example.edu", IP)).isFalse();
    }

    @Test
    void emailKeyIsCaseInsensitive() {
        LoginRateLimiter limiter = new LoginRateLimiter(new MutableClock());

        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            limiter.recordFailure(EMAIL.toUpperCase(), IP);
        }

        assertThat(limiter.isLocked(EMAIL, IP)).isTrue();
    }
}
