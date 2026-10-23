package com.githubaccessreport.github;

import com.githubaccessreport.github.wire.RateLimitJson;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether GitHub accepts our token and how much quota is left, via {@code GET /rate_limit}
 * (free: it does not count against the limit). Cached for a minute so health polling cannot add
 * load.
 *
 * <p>Deliberately <b>not</b> part of the readiness group: if GitHub is down the instance can still
 * serve cached reports, so it should stay in rotation. It shows up under {@code /actuator/health}.
 */
@Component("github")
public class GitHubHealthIndicator implements HealthIndicator {

    static final Duration CACHE_FOR = Duration.ofMinutes(1);

    private final GitHubHttp http;
    private final Clock clock;
    private Health cached;
    private Instant cachedAt = Instant.MIN;

    public GitHubHealthIndicator(GitHubHttp http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    @Override
    public synchronized Health health() {
        Instant now = clock.instant();
        if (cached == null || cachedAt.plus(CACHE_FOR).isBefore(now)) {
            cached = check();
            cachedAt = now;
        }
        return cached;
    }

    private Health check() {
        try {
            RateLimitJson.Resource core =
                    http.get("/rate_limit", RateLimitJson.class).resources().get("core");
            if (core == null) {
                return Health.unknown()
                        .withDetail("reason", "no core rate limit in response")
                        .build();
            }
            return Health.up()
                    .withDetail("rateLimitRemaining", core.remaining())
                    .withDetail("rateLimit", core.limit())
                    .withDetail("resetAt", Instant.ofEpochSecond(core.reset()))
                    .build();
        } catch (GitHubException.Unauthorized e) {
            return Health.down()
                    .withDetail("reason", "GitHub rejected the service token")
                    .build();
        } catch (RuntimeException e) {
            return Health.down()
                    .withDetail("reason", "GitHub unreachable or failing")
                    .withDetail("error", e.getClass().getSimpleName())
                    .build();
        }
    }
}
