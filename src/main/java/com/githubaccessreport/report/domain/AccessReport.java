package com.githubaccessreport.report.domain;

import java.time.Instant;
import java.util.List;

/**
 * An organization's access, as cached. Immutable: one instance is shared by every request that
 * hits the cache.
 *
 * @param repositories readable repositories, sorted by name (skipped ones are in {@code coverage})
 * @param grants       one row per (user, repository), sorted by login then repository name
 */
public record AccessReport(
        String organization,
        Instant generatedAt,
        List<Repository> repositories,
        List<AccessGrant> grants,
        Coverage coverage) {

    public AccessReport {
        repositories = List.copyOf(repositories);
        grants = List.copyOf(grants);
    }
}
