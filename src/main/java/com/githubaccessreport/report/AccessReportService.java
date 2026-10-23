package com.githubaccessreport.report;

import com.githubaccessreport.config.CacheConfig;
import com.githubaccessreport.config.ReportProperties;
import com.githubaccessreport.report.domain.AccessGrant;
import com.githubaccessreport.report.domain.AccessReport;
import com.githubaccessreport.report.domain.OrgAccessSnapshot;
import com.githubaccessreport.report.domain.Repository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Builds and caches one {@link AccessReport} per organization. Every view, filter and page is
 * computed from that cached report, so a single GitHub fetch serves all query shapes.
 */
@Service
public class AccessReportService {

    private static final Logger log = LoggerFactory.getLogger(AccessReportService.class);

    static final Comparator<AccessGrant> GRANT_ORDER = Comparator
            .comparing((AccessGrant grant) -> grant.user().login(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(grant -> grant.repository().name(), String.CASE_INSENSITIVE_ORDER);

    private final OrgAccessSource source;
    private final ReportProperties properties;
    private final Clock clock;

    public AccessReportService(OrgAccessSource source, ReportProperties properties, Clock clock) {
        this.source = source;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * {@code sync = true}: concurrent misses for the same organization wait for one build instead
     * of each hitting GitHub (cache stampede). Failures are not cached; reports with skipped
     * repositories are, because those gaps are deterministic and reported in {@code coverage}.
     *
     * @param organization normalized (lower-case) organization login
     */
    @Cacheable(cacheNames = CacheConfig.ACCESS_REPORTS, key = "#organization", sync = true)
    public AccessReport getReport(String organization) {
        Instant started = clock.instant();
        OrgAccessSnapshot snapshot = source.fetch(organization, started.plus(properties.syncDeadline()));

        List<AccessGrant> grants = deduplicate(snapshot.grants()).stream().sorted(GRANT_ORDER).toList();
        List<Repository> repositories = snapshot.repositories().stream()
                .sorted(Comparator.comparing(Repository::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        Instant generatedAt = clock.instant();
        log.info("Built access report for '{}': {} repositories, {} grants, complete={} in {}ms",
                organization, repositories.size(), grants.size(), snapshot.coverage().complete(),
                Duration.between(started, generatedAt).toMillis());
        return new AccessReport(organization, generatedAt, repositories, grants, snapshot.coverage());
    }

    /**
     * Offset pagination can return the same collaborator twice if the list shifts between pages;
     * keep one grant per (user, repository), the highest permission winning.
     */
    private static Collection<AccessGrant> deduplicate(List<AccessGrant> grants) {
        Map<String, AccessGrant> unique = new LinkedHashMap<>();
        for (AccessGrant grant : grants) {
            unique.merge(grant.user().id() + ":" + grant.repository().id(), grant,
                    (a, b) -> a.permission().compareTo(b.permission()) >= 0 ? a : b);
        }
        return unique.values();
    }
}
