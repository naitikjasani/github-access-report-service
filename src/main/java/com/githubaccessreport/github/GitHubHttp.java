package com.githubaccessreport.github;

import com.githubaccessreport.config.GitHubProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Transport for GitHub REST calls: one place for concurrency limits, retries, rate-limit handling
 * and pagination.
 *
 * <ul>
 *   <li><b>Concurrency:</b> a single semaphore shared by every in-flight report, so concurrent
 *       reports cannot add up past GitHub's secondary limits.
 *   <li><b>Transient failures</b> (5xx, timeouts, connection errors): retried with exponential
 *       backoff and jitter.
 *   <li><b>Secondary rate limit:</b> waits for {@code Retry-After} if it is short, otherwise fails.
 *   <li><b>Primary rate limit:</b> fails fast; waiting up to an hour inside a request helps nobody.
 * </ul>
 *
 * Blocking calls are deliberate: they run on virtual threads, and an interrupt (deadline
 * reached) aborts the in-flight HTTP exchange and any wait.
 */
@Component
public class GitHubHttp {

    private static final Logger log = LoggerFactory.getLogger(GitHubHttp.class);

    static final int PER_PAGE = 100;
    private static final int MAX_SECONDARY_LIMIT_WAITS = 2;
    private static final int MAX_ERROR_BODY_BYTES = 4096;

    private final RestClient restClient;
    private final RateLimitTracker rateLimitTracker;
    private final Clock clock;
    private final Semaphore permits;
    private final RetryTemplate retryTemplate;
    private final Duration maxRetryAfter;

    private final Counter retries;
    private final Counter secondaryLimitWaits;
    private final MeterRegistry meterRegistry;

    public GitHubHttp(
            RestClient gitHubApiRestClient,
            GitHubProperties properties,
            RateLimitTracker rateLimitTracker,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.restClient = gitHubApiRestClient;
        this.rateLimitTracker = rateLimitTracker;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.permits = new Semaphore(properties.maxConcurrency(), true);
        this.maxRetryAfter = properties.maxRetryAfter();
        this.retries = Counter.builder("github.retries")
                .description("GitHub calls retried after a transient failure (5xx, timeout, I/O)")
                .register(meterRegistry);
        this.secondaryLimitWaits = Counter.builder("github.ratelimit.secondary.waits")
                .description("Times we waited out a short GitHub secondary rate limit")
                .register(meterRegistry);
        this.retryTemplate = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(properties.maxRetries())
                .delay(Duration.ofMillis(200))
                .multiplier(2.0)
                .jitter(Duration.ofMillis(100))
                .maxDelay(Duration.ofSeconds(2))
                .predicate(ex -> ex instanceof GitHubException.Unavailable
                        && !Thread.currentThread().isInterrupted())
                .build());
        this.retryTemplate.setRetryListener(new RetryListener() {
            @Override
            public void beforeRetry(RetryPolicy retryPolicy, Retryable<?> retryable) {
                retries.increment();
            }
        });
    }

    /** GET a single JSON document. */
    public <T> T get(String uriTemplate, Class<T> type, Object... uriVariables) {
        return execute(uriTemplate, ParameterizedTypeReference.<T>forType(type), uriVariables)
                .body();
    }

    /** GET every page of a collection, following GitHub's {@code Link: rel="next"}. */
    public <T> List<T> getAllPages(
            String uriTemplate, ParameterizedTypeReference<List<T>> type, Object... uriVariables) {
        String pagedTemplate =
                uriTemplate + (uriTemplate.contains("?") ? "&" : "?") + "per_page=" + PER_PAGE + "&page={page}";
        List<T> items = new ArrayList<>();
        int page = 1;
        while (true) {
            Object[] variables = Arrays.copyOf(uriVariables, uriVariables.length + 1);
            variables[uriVariables.length] = page;

            Response<List<T>> response = execute(pagedTemplate, type, variables);
            if (response.body() != null) {
                items.addAll(response.body());
            }
            OptionalInt next = LinkHeader.nextPage(response.headers().getFirst(HttpHeaders.LINK));
            if (next.isEmpty()) {
                return items;
            }
            if (next.getAsInt() <= page) {
                throw new IllegalStateException("GitHub pagination did not advance past page " + page);
            }
            page = next.getAsInt();
        }
    }

    private <T> Response<T> execute(String uriTemplate, ParameterizedTypeReference<T> type, Object[] variables) {
        int secondaryLimitWaits = 0;
        while (true) {
            try {
                return retryTemplate.execute(() -> exchange(uriTemplate, type, variables));
            } catch (RetryException e) {
                Throwable failure = e.getLastException() != null ? e.getLastException() : e.getCause();
                if (failure instanceof GitHubException.SecondaryRateLimitExceeded limit
                        && secondaryLimitWaits < MAX_SECONDARY_LIMIT_WAITS
                        && limit.retryAfter().compareTo(maxRetryAfter) <= 0) {
                    secondaryLimitWaits++;
                    this.secondaryLimitWaits.increment();
                    log.warn(
                            "GitHub secondary rate limit on {}; waiting {}s (attempt {})",
                            uriTemplate,
                            limit.retryAfter().toSeconds(),
                            secondaryLimitWaits);
                    sleep(limit.retryAfter()
                            .plusMillis(ThreadLocalRandom.current().nextLong(250)));
                    continue;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("GitHub call cancelled: " + uriTemplate);
                }
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("GitHub call failed: " + uriTemplate, failure);
            }
        }
    }

    private <T> Response<T> exchange(String uriTemplate, ParameterizedTypeReference<T> type, Object[] variables) {
        acquirePermit();
        try {
            return restClient.get().uri(uriTemplate, variables).exchange((request, response) -> {
                HttpHeaders headers = response.getHeaders();
                rateLimitTracker.record(headers);
                int status = response.getStatusCode().value();
                if (response.getStatusCode().is2xxSuccessful()) {
                    return new Response<>(response.bodyTo(type), headers);
                }
                String body = readErrorBody(response.getBody());
                log.debug("GitHub returned HTTP {} for {}", status, uriTemplate);
                GitHubException failure = GitHubErrorClassifier.classify(status, headers, body, clock.instant());
                countRateLimit(failure);
                throw failure;
            });
        } catch (ResourceAccessException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("GitHub call cancelled: " + uriTemplate);
            }
            throw new GitHubException.Unavailable("Could not reach GitHub: " + e.getMessage(), 0, e);
        } catch (RestClientException e) {
            // The body failed to arrive (connection dropped mid-stream) -> transient; it arrived but
            // does not parse (schema drift) -> retrying would not help.
            if (NestedExceptionUtils.getRootCause(e) instanceof IOException) {
                throw new GitHubException.Unavailable(
                        "Connection to GitHub failed mid-response: " + e.getMessage(), 0, e);
            }
            throw new GitHubException.UnexpectedResponse("Unreadable GitHub response for " + uriTemplate, 200, e);
        } finally {
            permits.release();
        }
    }

    private void countRateLimit(GitHubException failure) {
        String type = switch (failure) {
            case GitHubException.PrimaryRateLimitExceeded ignored -> "primary";
            case GitHubException.SecondaryRateLimitExceeded ignored -> "secondary";
            default -> null;
        };
        if (type != null) {
            meterRegistry.counter("github.ratelimit.hits", "type", type).increment();
        }
    }

    private void acquirePermit() {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while waiting for a GitHub connection slot");
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while waiting for GitHub rate limit");
        }
    }

    private static String readErrorBody(InputStream body) throws IOException {
        return new String(body.readNBytes(MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8);
    }

    private record Response<T>(T body, HttpHeaders headers) {}
}
