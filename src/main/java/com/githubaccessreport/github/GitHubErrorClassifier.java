package com.githubaccessreport.github;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.HttpHeaders;

/**
 * Maps a non-2xx GitHub response to a {@link GitHubException}.
 *
 * <p>GitHub signals rate limits in several ways that all look like "403 Forbidden" at first glance:
 *
 * <ul>
 *   <li><b>Primary</b> (hourly quota): 403 or 429 with {@code x-ratelimit-remaining: 0}; wait until
 *       {@code x-ratelimit-reset}.
 *   <li><b>Secondary</b> (too many requests too quickly): 403 or 429 with {@code retry-after}, or a
 *       body mentioning a secondary rate limit; without the header GitHub asks for at least a minute.
 * </ul>
 *
 * Treating either as "no permission" silently drops data, so they are checked before plain 403.
 *
 * @see <a href="https://docs.github.com/en/rest/using-the-rest-api/troubleshooting-the-rest-api#rate-limit-errors">GitHub docs: rate limit errors</a>
 */
public final class GitHubErrorClassifier {

    static final Duration DEFAULT_SECONDARY_WAIT = Duration.ofSeconds(60);

    private GitHubErrorClassifier() {}

    public static GitHubException classify(int status, HttpHeaders headers, String body, Instant now) {
        if (status == 403 || status == 429) {
            if ("0".equals(headers.getFirst("x-ratelimit-remaining"))) {
                return new GitHubException.PrimaryRateLimitExceeded(status, resetAt(headers, now));
            }
            String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) {
                return new GitHubException.SecondaryRateLimitExceeded(status, parseSeconds(retryAfter));
            }
            if (status == 429 || mentionsSecondaryLimit(body)) {
                return new GitHubException.SecondaryRateLimitExceeded(status, DEFAULT_SECONDARY_WAIT);
            }
            return new GitHubException.Forbidden("GitHub denied access (HTTP 403)");
        }
        if (status == 401) {
            return new GitHubException.Unauthorized("GitHub rejected the service token (HTTP 401)");
        }
        if (status == 404) {
            return new GitHubException.NotFound("Not found on GitHub (HTTP 404)");
        }
        if (status >= 500) {
            return new GitHubException.Unavailable("GitHub server error (HTTP " + status + ")", status, null);
        }
        return new GitHubException.UnexpectedResponse(status);
    }

    private static Instant resetAt(HttpHeaders headers, Instant now) {
        String reset = headers.getFirst("x-ratelimit-reset");
        if (reset != null) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(reset.strip()));
            } catch (NumberFormatException ignored) {
                // fall through to a conservative default
            }
        }
        return now.plus(DEFAULT_SECONDARY_WAIT);
    }

    private static Duration parseSeconds(String retryAfter) {
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(retryAfter.strip())));
        } catch (NumberFormatException e) {
            return DEFAULT_SECONDARY_WAIT;
        }
    }

    private static boolean mentionsSecondaryLimit(String body) {
        return body != null && body.toLowerCase(Locale.ROOT).contains("secondary rate limit");
    }
}
