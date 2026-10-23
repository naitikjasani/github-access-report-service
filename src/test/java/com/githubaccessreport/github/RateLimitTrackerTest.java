package com.githubaccessreport.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class RateLimitTrackerTest {

    private final RateLimitTracker tracker = new RateLimitTracker();

    @Test
    void keepsLowestRemainingWithinTheSameWindow() {
        tracker.record(headers("4990", "1000"));
        tracker.record(headers("4980", "1000"));
        tracker.record(headers("4985", "1000")); // older response arriving late

        assertThat(tracker.latest("core"))
                .hasValueSatisfying(s -> assertThat(s.remaining()).isEqualTo(4980));
    }

    @Test
    void newWindowReplacesOldOne() {
        tracker.record(headers("3", "1000"));
        tracker.record(headers("4999", "4600"));

        assertThat(tracker.latest("core"))
                .hasValueSatisfying(s -> assertThat(s.remaining()).isEqualTo(4999));
    }

    @Test
    void ignoresResponsesWithoutRateLimitHeaders() {
        tracker.record(new HttpHeaders());

        assertThat(tracker.latest("core")).isEmpty();
    }

    private static HttpHeaders headers(String remaining, String reset) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-ratelimit-limit", "5000");
        headers.add("x-ratelimit-remaining", remaining);
        headers.add("x-ratelimit-reset", reset);
        headers.add("x-ratelimit-resource", "core");
        return headers;
    }
}
