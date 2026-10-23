package com.githubaccessreport.github;

import java.time.Duration;
import java.time.Instant;

/**
 * Every way a GitHub call can fail, as a closed set. Callers decide per type whether to retry, skip
 * the resource, or fail the whole report, and the compiler keeps that decision exhaustive.
 */
public abstract sealed class GitHubException extends RuntimeException
        permits GitHubException.NotFound,
                GitHubException.Forbidden,
                GitHubException.Unauthorized,
                GitHubException.PrimaryRateLimitExceeded,
                GitHubException.SecondaryRateLimitExceeded,
                GitHubException.Unavailable,
                GitHubException.UnexpectedResponse {

    /** HTTP status from GitHub, or 0 when no response was received. */
    private final int status;

    GitHubException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** 404: the organization or repository does not exist, or the token cannot see it. */
    public static final class NotFound extends GitHubException {
        public NotFound(String message) {
            super(message, 404, null);
        }
    }

    /** 403 that is not a rate limit: the token lacks permission for this resource. */
    public static final class Forbidden extends GitHubException {
        public Forbidden(String message) {
            super(message, 403, null);
        }
    }

    /** 401: the service's own token is invalid, expired or revoked. */
    public static final class Unauthorized extends GitHubException {
        public Unauthorized(String message) {
            super(message, 401, null);
        }
    }

    /** The hourly quota is used up; nothing succeeds until {@link #resetAt()}. */
    public static final class PrimaryRateLimitExceeded extends GitHubException {
        private final Instant resetAt;

        public PrimaryRateLimitExceeded(int status, Instant resetAt) {
            super("GitHub primary rate limit exhausted until " + resetAt, status, null);
            this.resetAt = resetAt;
        }

        public Instant resetAt() {
            return resetAt;
        }
    }

    /** Abuse-protection limit (too many requests too quickly); GitHub says when to retry. */
    public static final class SecondaryRateLimitExceeded extends GitHubException {
        private final Duration retryAfter;

        public SecondaryRateLimitExceeded(int status, Duration retryAfter) {
            super("GitHub secondary rate limit hit, retry after " + retryAfter.toSeconds() + "s", status, null);
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }

    /** 5xx, timeout or connection failure: transient, worth retrying. */
    public static final class Unavailable extends GitHubException {
        public Unavailable(String message, int status, Throwable cause) {
            super(message, status, cause);
        }
    }

    /** Any other non-2xx response, or a body we cannot parse; not retried. */
    public static final class UnexpectedResponse extends GitHubException {
        public UnexpectedResponse(int status) {
            super("Unexpected GitHub response (HTTP " + status + ")", status, null);
        }

        public UnexpectedResponse(String message, int status, Throwable cause) {
            super(message, status, cause);
        }
    }
}
