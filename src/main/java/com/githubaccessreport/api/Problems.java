package com.githubaccessreport.api;

import com.githubaccessreport.github.GitHubException;
import com.githubaccessreport.report.OrganizationNotFoundException;
import com.githubaccessreport.report.RateLimitBudgetExceededException;
import com.githubaccessreport.report.ReportTimeoutException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The single mapping from failures to RFC 9457 problems with a stable {@code code}, used for HTTP
 * responses and for failed asynchronous jobs alike.
 *
 * <p>The switch over the sealed {@link GitHubException} has no default branch, so adding a new
 * GitHub failure type does not compile until it is given a deliberate response.
 */
@Component
public class Problems {

    private static final Logger log = LoggerFactory.getLogger(Problems.class);

    static final String DOCS = "https://github.com/naitikjasani/github-access-report-service/blob/main/docs/errors.md#";

    private final Clock clock;
    private final TraceIds traceIds;

    public Problems(Clock clock, TraceIds traceIds) {
        this.clock = clock;
        this.traceIds = traceIds;
    }

    public ResponseEntity<ProblemDetail> from(Throwable failure) {
        return switch (failure) {
            case OrganizationNotFoundException e ->
                problem(HttpStatus.NOT_FOUND, "organization-not-found", "Organization not found", e.getMessage());
            case OrganizationNotAllowedException e ->
                problem(HttpStatus.FORBIDDEN, "organization-not-allowed", "Organization not allowed", e.getMessage());
            case ReportJobNotFoundException e ->
                problem(HttpStatus.NOT_FOUND, "report-job-not-found", "Report job not found", e.getMessage());
            case RateLimitBudgetExceededException e -> {
                log.warn(e.getMessage());
                ProblemDetail body = body(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "rate-limit-budget",
                        "Not enough GitHub quota for this report",
                        e.getMessage());
                body.setProperty("requiredRequests", e.required());
                body.setProperty("remainingRequests", e.remaining());
                body.setProperty("resetAt", e.resetAt());
                yield withRetryAfter(body, until(e.resetAt()));
            }
            case ReportTimeoutException e -> {
                log.warn(e.getMessage());
                yield problem(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "report-timeout",
                        "Report took too long",
                        e.getMessage()
                                + ". For large organizations, start an asynchronous report with POST /api/v1/reports.");
            }
            case GitHubException e -> fromGitHub(e);
            default -> {
                log.error("Unexpected error", failure);
                yield problem(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "internal-error",
                        "Internal error",
                        "An unexpected error occurred.");
            }
        };
    }

    private ResponseEntity<ProblemDetail> fromGitHub(GitHubException failure) {
        return switch (failure) {
            case GitHubException.PrimaryRateLimitExceeded e -> {
                log.warn("GitHub primary rate limit exhausted until {}", e.resetAt());
                ProblemDetail body = body(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "rate-limited",
                        "GitHub rate limit exhausted",
                        "The service's GitHub quota is used up until " + e.resetAt() + ".");
                body.setProperty("resetAt", e.resetAt());
                yield withRetryAfter(body, until(e.resetAt()));
            }
            case GitHubException.SecondaryRateLimitExceeded e -> {
                log.warn(
                        "GitHub secondary rate limit; asked to wait {}s",
                        e.retryAfter().toSeconds());
                yield withRetryAfter(
                        body(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "upstream-throttled",
                                "GitHub is throttling requests",
                                "GitHub asked us to slow down. Retry after the indicated delay."),
                        e.retryAfter());
            }
            case GitHubException.Unauthorized e -> {
                log.error("GitHub rejected the service token; check GITHUB_TOKEN ({})", e.getMessage());
                yield problem(
                        HttpStatus.BAD_GATEWAY,
                        "upstream-auth-failed",
                        "GitHub rejected the service credentials",
                        "The service is misconfigured. This is not a problem with your request.");
            }
            case GitHubException.Forbidden e -> {
                log.warn("GitHub denied organization-level access: {}", e.getMessage());
                yield problem(
                        HttpStatus.BAD_GATEWAY,
                        "upstream-forbidden",
                        "GitHub denied access",
                        "The service token is not allowed to read this organization.");
            }
            case GitHubException.Unavailable e -> upstreamUnavailable(e);
            case GitHubException.UnexpectedResponse e -> upstreamUnavailable(e);
            case GitHubException.NotFound e -> upstreamUnavailable(e);
        };
    }

    private ResponseEntity<ProblemDetail> upstreamUnavailable(GitHubException e) {
        log.error("GitHub call failed (status {}): {}", e.status(), e.getMessage(), e.getCause());
        return problem(
                HttpStatus.BAD_GATEWAY,
                "upstream-unavailable",
                "GitHub request failed",
                "GitHub did not respond successfully after retries.");
    }

    ProblemDetail body(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(DOCS + code));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return withTraceId(problem);
    }

    /** Lets a caller quote one id that matches the server's logs for this request. */
    ProblemDetail withTraceId(ProblemDetail problem) {
        traceIds.current().ifPresent(id -> problem.setProperty("traceId", id));
        return problem;
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title, String detail) {
        return ResponseEntity.status(status).body(body(status, code, title, detail));
    }

    private static ResponseEntity<ProblemDetail> withRetryAfter(ProblemDetail body, Duration retryAfter) {
        long seconds = Math.max(1, retryAfter.toSeconds());
        return ResponseEntity.status(body.getStatus())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(body);
    }

    private Duration until(Instant instant) {
        return Duration.between(clock.instant(), instant);
    }
}
