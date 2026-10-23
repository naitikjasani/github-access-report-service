package com.githubaccessreport.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;

class GitHubErrorClassifierTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    static Stream<Arguments> responses() {
        return Stream.of(
                Arguments.of(
                        "403 with remaining=0 is the primary limit",
                        403,
                        headers("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1767229200"),
                        "",
                        GitHubException.PrimaryRateLimitExceeded.class),
                Arguments.of(
                        "429 with remaining=0 is the primary limit",
                        429,
                        headers("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1767229200"),
                        "",
                        GitHubException.PrimaryRateLimitExceeded.class),
                Arguments.of(
                        "403 with Retry-After is a secondary limit",
                        403,
                        headers("retry-after", "30", "x-ratelimit-remaining", "4000"),
                        "",
                        GitHubException.SecondaryRateLimitExceeded.class),
                Arguments.of(
                        "403 whose body mentions the secondary limit",
                        403,
                        headers(),
                        "{\"message\":\"You have exceeded a secondary rate limit.\"}",
                        GitHubException.SecondaryRateLimitExceeded.class),
                Arguments.of(
                        "429 without headers is still a rate limit",
                        429,
                        headers(),
                        "",
                        GitHubException.SecondaryRateLimitExceeded.class),
                Arguments.of(
                        "plain 403 is a permission problem",
                        403,
                        headers("x-ratelimit-remaining", "4999"),
                        "{\"message\":\"Must have push access to view repository collaborators.\"}",
                        GitHubException.Forbidden.class),
                Arguments.of("401 is our credential problem", 401, headers(), "", GitHubException.Unauthorized.class),
                Arguments.of("404", 404, headers(), "", GitHubException.NotFound.class),
                Arguments.of("500 is transient", 500, headers(), "", GitHubException.Unavailable.class),
                Arguments.of("502 is transient", 502, headers(), "", GitHubException.Unavailable.class),
                Arguments.of("422 is unexpected", 422, headers(), "", GitHubException.UnexpectedResponse.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("responses")
    void classifies(
            String description,
            int status,
            HttpHeaders headers,
            String body,
            Class<? extends GitHubException> expected) {
        assertThat(GitHubErrorClassifier.classify(status, headers, body, NOW)).isInstanceOf(expected);
    }

    @Test
    void primaryLimitCarriesResetTime() {
        var exception = (GitHubException.PrimaryRateLimitExceeded) GitHubErrorClassifier.classify(
                403, headers("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1767229200"), "", NOW);

        assertThat(exception.resetAt()).isEqualTo(Instant.ofEpochSecond(1767229200));
    }

    @Test
    void secondaryLimitUsesRetryAfterOrDefaultsToOneMinute() {
        var withHeader = (GitHubException.SecondaryRateLimitExceeded)
                GitHubErrorClassifier.classify(403, headers("retry-after", "30"), "", NOW);
        var withoutHeader = (GitHubException.SecondaryRateLimitExceeded)
                GitHubErrorClassifier.classify(403, headers(), "secondary rate limit", NOW);

        assertThat(withHeader.retryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(withoutHeader.retryAfter()).isEqualTo(Duration.ofSeconds(60));
    }

    private static HttpHeaders headers(String... pairs) {
        HttpHeaders headers = new HttpHeaders();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.add(pairs[i], pairs[i + 1]);
        }
        return headers;
    }
}
