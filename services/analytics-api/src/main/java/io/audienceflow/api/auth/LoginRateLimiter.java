package io.audienceflow.api.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Small in-memory, thread-safe failed-login limiter keyed by email + client IP.
 *
 * <p>After {@link #MAX_ATTEMPTS} failures inside {@link #WINDOW} the key is locked out for the
 * remainder of the window and {@link #isLocked} reports {@code true}. A successful login clears
 * the key. Entries are bounded: stale entries are evicted lazily on access and, as a safety net,
 * a full sweep runs once the map grows past {@link #MAX_ENTRIES}.
 */
@Component
public class LoginRateLimiter {
    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_ENTRIES = 10_000;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginRateLimiter() {
        this(Instant::now);
    }

    LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Returns true if this email/IP is currently locked out and the request must be rejected. */
    public boolean isLocked(String email, String clientIp) {
        Attempt attempt = attempts.get(key(email, clientIp));
        if (attempt == null) {
            return false;
        }
        return attempt.isLocked(clock.now());
    }

    /** Records a failed login attempt for this email/IP. */
    public void recordFailure(String email, String clientIp) {
        Instant now = clock.now();
        evictStaleIfNeeded(now);
        attempts.compute(key(email, clientIp), (ignored, existing) -> {
            if (existing == null || existing.isExpired(now)) {
                return new Attempt(1, now);
            }
            return new Attempt(existing.count() + 1, now);
        });
    }

    /** Clears any recorded failures for this email/IP after a successful login. */
    public void recordSuccess(String email, String clientIp) {
        attempts.remove(key(email, clientIp));
    }

    private void evictStaleIfNeeded(Instant now) {
        if (attempts.size() <= MAX_ENTRIES) {
            return;
        }
        attempts.values().removeIf(attempt -> attempt.isExpired(now));
    }

    private static String key(String email, String clientIp) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        String normalizedIp = clientIp == null ? "" : clientIp;
        return normalizedEmail + "|" + normalizedIp;
    }

    private record Attempt(int count, Instant lastFailure) {
        boolean isExpired(Instant now) {
            return now.isAfter(lastFailure.plus(WINDOW));
        }

        boolean isLocked(Instant now) {
            return count >= MAX_ATTEMPTS && !isExpired(now);
        }
    }

    /** Minimal time source so the window/lockout logic can be tested deterministically. */
    interface Clock {
        Instant now();
    }
}
