package com.githubaccessreport.github;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Remembers the latest {@code x-ratelimit-*} values GitHub sent, per resource ("core", "graphql",
 * ...). Used for the pre-flight budget check and exported as a metric.
 */
@Component
public class RateLimitTracker {

    public record Snapshot(String resource, long limit, long remaining, Instant resetAt) {}

    private final Map<String, Snapshot> latest = new ConcurrentHashMap<>();

    public void record(HttpHeaders headers) {
        Long limit = parse(headers.getFirst("x-ratelimit-limit"));
        Long remaining = parse(headers.getFirst("x-ratelimit-remaining"));
        Long reset = parse(headers.getFirst("x-ratelimit-reset"));
        if (limit == null || remaining == null || reset == null) {
            return;
        }
        String resource =
                Optional.ofNullable(headers.getFirst("x-ratelimit-resource")).orElse("core");
        Snapshot observed = new Snapshot(resource, limit, remaining, Instant.ofEpochSecond(reset));

        // Parallel responses arrive out of order: within one reset window the smallest
        // "remaining" is the most recent truth; a later reset means a new window.
        latest.merge(resource, observed, (current, next) -> {
            if (next.resetAt().isAfter(current.resetAt())) {
                return next;
            }
            if (next.resetAt().equals(current.resetAt()) && next.remaining() < current.remaining()) {
                return next;
            }
            return current;
        });
    }

    public Optional<Snapshot> latest(String resource) {
        return Optional.ofNullable(latest.get(resource));
    }

    private static Long parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
