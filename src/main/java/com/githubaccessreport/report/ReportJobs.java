package com.githubaccessreport.report;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.githubaccessreport.config.CacheConfig;
import com.githubaccessreport.config.ReportProperties;
import com.githubaccessreport.report.domain.AccessReport;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * Asynchronous report generation for organizations too large to build within one HTTP request.
 *
 * <p>A job builds the report with {@code report.async-deadline} instead of the synchronous one,
 * then publishes it into the report cache, so the normal views, findings and CSV export serve it
 * without another GitHub call. One job runs per organization at a time: submitting again while one
 * is queued or running returns the same job. If a fresh report is already cached, the job
 * completes immediately without calling GitHub.
 *
 * <p>Jobs live in memory for an hour: they are lost on restart and not shared between instances,
 * which matches this single-instance deployment (see ADR-0003).
 */
@Service
public class ReportJobs {

    private static final Logger log = LoggerFactory.getLogger(ReportJobs.class);

    public enum Status {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    /** {@code failure} is kept as the exception so the API can map it to the same problem as a sync call. */
    public record Job(
            UUID id,
            String organization,
            Status status,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Instant reportGeneratedAt,
            Throwable failure) {

        boolean active() {
            return status == Status.QUEUED || status == Status.RUNNING;
        }
    }

    private final AccessReportService reports;
    private final CacheManager cacheManager;
    private final ReportProperties properties;
    private final Clock clock;
    private final Cache<UUID, Job> jobs = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(1_000)
            .build();
    private final ConcurrentMap<String, UUID> activeByOrganization = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ReportJobs(
            AccessReportService reports, CacheManager cacheManager, ReportProperties properties, Clock clock) {
        this.reports = reports;
        this.cacheManager = cacheManager;
        this.properties = properties;
        this.clock = clock;
    }

    /** @param organization normalized (lower-case), already allow-listed */
    public Job submit(String organization) {
        UUID id = activeByOrganization.compute(organization, (org, existing) -> {
            if (existing != null && find(existing).map(Job::active).orElse(false)) {
                return existing;
            }
            return start(org);
        });
        return find(id).orElseThrow();
    }

    public Optional<Job> find(UUID id) {
        return Optional.ofNullable(jobs.getIfPresent(id));
    }

    private UUID start(String organization) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        AccessReport cached = reportCache().get(organization, AccessReport.class);
        if (cached != null) {
            jobs.put(id, new Job(id, organization, Status.SUCCEEDED, now, now, now, cached.generatedAt(), null));
            return id;
        }
        jobs.put(id, new Job(id, organization, Status.QUEUED, now, null, null, null, null));
        executor.submit(() -> run(id, organization));
        return id;
    }

    private void run(UUID id, String organization) {
        update(
                id,
                job -> new Job(id, organization, Status.RUNNING, job.createdAt(), clock.instant(), null, null, null));
        try {
            AccessReport report = reports.build(organization, properties.asyncDeadline());
            reportCache().put(organization, report);
            update(
                    id,
                    job -> new Job(
                            id,
                            organization,
                            Status.SUCCEEDED,
                            job.createdAt(),
                            job.startedAt(),
                            clock.instant(),
                            report.generatedAt(),
                            null));
        } catch (RuntimeException e) {
            log.warn("Report job {} for '{}' failed: {}", id, organization, e.getMessage());
            update(
                    id,
                    job -> new Job(
                            id,
                            organization,
                            Status.FAILED,
                            job.createdAt(),
                            job.startedAt(),
                            clock.instant(),
                            null,
                            e));
        } finally {
            activeByOrganization.remove(organization, id);
        }
    }

    private void update(UUID id, java.util.function.UnaryOperator<Job> change) {
        jobs.asMap().computeIfPresent(id, (key, job) -> change.apply(job));
    }

    private org.springframework.cache.Cache reportCache() {
        return Objects.requireNonNull(cacheManager.getCache(CacheConfig.ACCESS_REPORTS));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
