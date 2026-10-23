package com.githubaccessreport.api;

import com.githubaccessreport.github.GitHubException;
import com.githubaccessreport.report.OrganizationNotFoundException;
import com.githubaccessreport.report.RateLimitBudgetExceededException;
import com.githubaccessreport.report.ReportTimeoutException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every failure to an RFC 9457 problem ({@code application/problem+json}) with a stable
 * {@code code}. Extending {@link ResponseEntityExceptionHandler} keeps Spring's own 4xx responses
 * (unknown path, bad parameter, wrong method) as 4xx instead of a catch-all 500.
 *
 * <p>Upstream failures are 502/503/504, never 401: a bad GitHub token is the server's problem, not
 * the caller's.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    static final String DOCS = "https://github.com/naitikjasani/github-access-report-service/blob/main/docs/errors.md#";

    private final Clock clock;

    public ProblemDetailsAdvice(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(OrganizationNotFoundException.class)
    ResponseEntity<ProblemDetail> organizationNotFound(OrganizationNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "organization-not-found", "Organization not found", e.getMessage());
    }

    @ExceptionHandler(OrganizationNotAllowedException.class)
    ResponseEntity<ProblemDetail> organizationNotAllowed(OrganizationNotAllowedException e) {
        return problem(HttpStatus.FORBIDDEN, "organization-not-allowed", "Organization not allowed", e.getMessage());
    }

    @ExceptionHandler(GitHubException.PrimaryRateLimitExceeded.class)
    ResponseEntity<ProblemDetail> primaryRateLimit(GitHubException.PrimaryRateLimitExceeded e) {
        log.warn("GitHub primary rate limit exhausted until {}", e.resetAt());
        ProblemDetail body = body(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "GitHub rate limit exhausted",
                "The service's GitHub quota is used up until " + e.resetAt() + ".");
        body.setProperty("resetAt", e.resetAt());
        return withRetryAfter(body, until(e.resetAt()));
    }

    @ExceptionHandler(RateLimitBudgetExceededException.class)
    ResponseEntity<ProblemDetail> budget(RateLimitBudgetExceededException e) {
        log.warn(e.getMessage());
        ProblemDetail body = body(HttpStatus.TOO_MANY_REQUESTS, "rate-limit-budget",
                "Not enough GitHub quota for this report", e.getMessage());
        body.setProperty("requiredRequests", e.required());
        body.setProperty("remainingRequests", e.remaining());
        body.setProperty("resetAt", e.resetAt());
        return withRetryAfter(body, until(e.resetAt()));
    }

    @ExceptionHandler(GitHubException.SecondaryRateLimitExceeded.class)
    ResponseEntity<ProblemDetail> secondaryRateLimit(GitHubException.SecondaryRateLimitExceeded e) {
        log.warn("GitHub secondary rate limit; asked to wait {}s", e.retryAfter().toSeconds());
        ProblemDetail body = body(HttpStatus.SERVICE_UNAVAILABLE, "upstream-throttled", "GitHub is throttling requests",
                "GitHub asked us to slow down. Retry after the indicated delay.");
        return withRetryAfter(body, e.retryAfter());
    }

    @ExceptionHandler(GitHubException.Unauthorized.class)
    ResponseEntity<ProblemDetail> upstreamAuth(GitHubException.Unauthorized e) {
        log.error("GitHub rejected the service token; check GITHUB_TOKEN ({})", e.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "upstream-auth-failed", "GitHub rejected the service credentials",
                "The service is misconfigured. This is not a problem with your request.");
    }

    @ExceptionHandler(GitHubException.Forbidden.class)
    ResponseEntity<ProblemDetail> upstreamForbidden(GitHubException.Forbidden e) {
        log.warn("GitHub denied organization-level access: {}", e.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "upstream-forbidden", "GitHub denied access",
                "The service token is not allowed to read this organization.");
    }

    @ExceptionHandler({
        GitHubException.Unavailable.class,
        GitHubException.UnexpectedResponse.class,
        GitHubException.NotFound.class
    })
    ResponseEntity<ProblemDetail> upstreamUnavailable(GitHubException e) {
        log.error("GitHub call failed (status {}): {}", e.status(), e.getMessage(), e.getCause());
        return problem(HttpStatus.BAD_GATEWAY, "upstream-unavailable", "GitHub request failed",
                "GitHub did not respond successfully after retries.");
    }

    @ExceptionHandler(ReportTimeoutException.class)
    ResponseEntity<ProblemDetail> timeout(ReportTimeoutException e) {
        log.warn(e.getMessage());
        return problem(HttpStatus.GATEWAY_TIMEOUT, "report-timeout", "Report took too long", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "An unexpected error occurred.");
    }

    /** Name the offending parameter instead of Spring's generic "Validation failure". */
    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getParameterValidationResults().stream()
                .map(result -> result.getMethodParameter().getParameterName() + " "
                        + result.getResolvableErrors().stream()
                                .map(MessageSourceResolvable::getDefaultMessage)
                                .collect(Collectors.joining(", ")))
                .collect(Collectors.joining("; "));
        ProblemDetail body = ex.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        body.setDetail(detail);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** Give Spring's built-in problems (400, 404, 405, ...) the same {@code code} and {@code type} shape. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            String code = switch (statusCode.value()) {
                case 400 -> "invalid-request";
                case 404 -> "not-found";
                case 405 -> "method-not-allowed";
                case 406 -> "not-acceptable";
                default -> "http-" + statusCode.value();
            };
            problem.setType(URI.create(DOCS + code));
            problem.setProperty("code", code);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    static ProblemDetail body(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(DOCS + code));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title, String detail) {
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
