package com.githubaccessreport.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for talking to the GitHub REST API. Validated at startup so a missing token fails fast
 * instead of on the first request.
 */
@Validated
@ConfigurationProperties("github")
public record GitHubProperties(
        @NotBlank(message = "must be set (export GITHUB_TOKEN=...)") String token,
        @NotNull URI baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(1) @Max(50) int maxConcurrency,
        @Min(0) @Max(5) int maxRetries,
        @NotNull Duration maxRetryAfter) {

    /** Records print every component by default; never let the token reach a log line. */
    @Override
    public String toString() {
        return "GitHubProperties[token=****, baseUrl=%s, connectTimeout=%s, readTimeout=%s, maxConcurrency=%d, maxRetries=%d, maxRetryAfter=%s]"
                .formatted(baseUrl, connectTimeout, readTimeout, maxConcurrency, maxRetries, maxRetryAfter);
    }
}
