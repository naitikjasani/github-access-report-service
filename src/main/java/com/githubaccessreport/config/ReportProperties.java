package com.githubaccessreport.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Report generation, caching and access policy.
 *
 * @param syncDeadline  upper bound for building one report synchronously
 * @param asyncDeadline upper bound for an asynchronous report job (POST /api/v1/reports)
 * @param cacheTtl      how long a generated report is served before it is rebuilt
 * @param cacheMaxSize  maximum number of organizations kept in the cache
 * @param allowedOrgs   if non-empty, only these organizations may be queried
 * @param apiKey        if set, {@code /api/**} requires it in the {@code X-API-Key} header
 */
@Validated
@ConfigurationProperties("report")
public record ReportProperties(
        @NotNull Duration syncDeadline,
        @NotNull Duration asyncDeadline,
        @NotNull Duration cacheTtl,
        @Min(1) long cacheMaxSize,
        Set<String> allowedOrgs,
        String apiKey) {

    public ReportProperties {
        allowedOrgs = allowedOrgs == null
                ? Set.of()
                : allowedOrgs.stream()
                        .map(String::strip)
                        .filter(org -> !org.isEmpty())
                        .map(org -> org.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAllowed(String normalizedOrg) {
        return allowedOrgs.isEmpty() || allowedOrgs.contains(normalizedOrg);
    }

    public boolean requiresApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** The API key must never reach a log line. */
    @Override
    public String toString() {
        return "ReportProperties[syncDeadline=%s, asyncDeadline=%s, cacheTtl=%s, cacheMaxSize=%d, allowedOrgs=%s, apiKey=%s]"
                .formatted(
                        syncDeadline,
                        asyncDeadline,
                        cacheTtl,
                        cacheMaxSize,
                        allowedOrgs,
                        requiresApiKey() ? "****" : "none");
    }
}
