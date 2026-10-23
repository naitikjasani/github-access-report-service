package com.githubaccessreport.github;

import com.githubaccessreport.github.wire.CollaboratorJson;
import com.githubaccessreport.github.wire.RepositoryJson;
import com.githubaccessreport.github.wire.UserJson;
import com.githubaccessreport.report.OrgAccessSource;
import com.githubaccessreport.report.OrganizationNotFoundException;
import com.githubaccessreport.report.RateLimitBudgetExceededException;
import com.githubaccessreport.report.ReportTimeoutException;
import com.githubaccessreport.report.domain.AccessGrant;
import com.githubaccessreport.report.domain.Affiliation;
import com.githubaccessreport.report.domain.Coverage;
import com.githubaccessreport.report.domain.OrgAccessSnapshot;
import com.githubaccessreport.report.domain.Permission;
import com.githubaccessreport.report.domain.Repository;
import com.githubaccessreport.report.domain.SkippedRepository;
import com.githubaccessreport.report.domain.User;
import com.githubaccessreport.report.domain.Visibility;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads an organization's access through the REST API: one paginated call for repositories, two
 * for membership, then one per repository for collaborators, fanned out on virtual threads.
 *
 * <p>Concurrency is bounded by {@link GitHubHttp}'s shared limit, not here, so 1,000 repositories
 * become 1,000 cheap virtual threads queueing for a few connection slots. The whole fan-out is
 * bounded by the caller's deadline; on timeout or the first fatal error, outstanding calls are
 * interrupted, which aborts their HTTP exchanges.
 */
@Component
public class RestOrgAccessSource implements OrgAccessSource {

    private static final Logger log = LoggerFactory.getLogger(RestOrgAccessSource.class);

    /** Calls made besides one per repository: owners + outside collaborators. */
    private static final int MEMBERSHIP_CALLS = 2;

    private final GitHubRestClient github;
    private final RateLimitTracker rateLimitTracker;
    private final Clock clock;

    public RestOrgAccessSource(GitHubRestClient github, RateLimitTracker rateLimitTracker, Clock clock) {
        this.github = github;
        this.rateLimitTracker = rateLimitTracker;
        this.clock = clock;
    }

    @Override
    public OrgAccessSnapshot fetch(String organization, Instant deadline) {
        Duration budget = Duration.between(clock.instant(), deadline);
        List<RepositoryJson> repositories = listRepositories(organization);
        checkRateLimitBudget(repositories.size() + MEMBERSHIP_CALLS);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Optional<Set<Long>>> owners = executor.submit(() -> userIds(() -> github.listOwners(organization)));
            Future<Optional<Set<Long>>> outside =
                    executor.submit(() -> userIds(() -> github.listOutsideCollaborators(organization)));

            CompletionService<RepositoryResult> results = new ExecutorCompletionService<>(executor);
            for (RepositoryJson repository : repositories) {
                results.submit(() -> readRepository(organization, repository));
            }

            try {
                List<RepositoryResult> completed = new ArrayList<>(repositories.size());
                for (int i = 0; i < repositories.size(); i++) {
                    completed.add(next(results, deadline));
                }
                Membership membership = new Membership(await(owners, deadline), await(outside, deadline));
                return toSnapshot(repositories.size(), completed, membership);
            } catch (TimeoutException e) {
                executor.shutdownNow();
                throw new ReportTimeoutException(organization, budget);
            } catch (RuntimeException e) {
                executor.shutdownNow();
                throw e;
            }
        }
    }

    private List<RepositoryJson> listRepositories(String organization) {
        try {
            return github.listRepositories(organization);
        } catch (GitHubException.NotFound e) {
            throw new OrganizationNotFoundException(organization);
        }
    }

    /**
     * Fails before fanning out rather than halfway through: a report the quota cannot finish would
     * burn the remaining budget and still return an error.
     */
    private void checkRateLimitBudget(int requiredCalls) {
        rateLimitTracker.latest("core").ifPresent(limit -> {
            if (limit.remaining() < requiredCalls && limit.resetAt().isAfter(clock.instant())) {
                throw new RateLimitBudgetExceededException(requiredCalls, limit.remaining(), limit.resetAt());
            }
        });
    }

    private RepositoryResult readRepository(String organization, RepositoryJson repository) {
        try {
            return new RepositoryResult(repository, github.listCollaborators(organization, repository.name()), null);
        } catch (GitHubException.Forbidden e) {
            log.warn("Skipping {}/{}: token cannot list collaborators", organization, repository.name());
            return new RepositoryResult(repository, List.of(), SkippedRepository.Reason.INSUFFICIENT_PERMISSION);
        } catch (GitHubException.NotFound e) {
            log.warn("Skipping {}/{}: repository disappeared during the report", organization, repository.name());
            return new RepositoryResult(repository, List.of(), SkippedRepository.Reason.NOT_FOUND);
        }
    }

    /** Membership lists need org-level read access; without it, affiliation is reported as unknown. */
    private static Optional<Set<Long>> userIds(Supplier<List<UserJson>> call) {
        try {
            return Optional.of(call.get().stream().map(UserJson::id).collect(Collectors.toUnmodifiableSet()));
        } catch (GitHubException.Forbidden | GitHubException.NotFound e) {
            log.warn("Cannot read organization membership ({}); affiliations will be UNKNOWN", e.getMessage());
            return Optional.empty();
        }
    }

    private RepositoryResult next(CompletionService<RepositoryResult> results, Instant deadline)
            throws TimeoutException {
        try {
            Future<RepositoryResult> done = results.poll(remaining(deadline), TimeUnit.MILLISECONDS);
            if (done == null) {
                throw new TimeoutException();
            }
            return done.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Report generation interrupted");
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    private <T> T await(Future<T> future, Instant deadline) throws TimeoutException {
        try {
            return future.get(remaining(deadline), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Report generation interrupted");
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    private long remaining(Instant deadline) {
        return Math.max(0, Duration.between(clock.instant(), deadline).toMillis());
    }

    private static RuntimeException unwrap(ExecutionException e) {
        return e.getCause() instanceof RuntimeException runtime
                ? runtime
                : new IllegalStateException("GitHub call failed", e.getCause());
    }

    private static OrgAccessSnapshot toSnapshot(int listed, List<RepositoryResult> results, Membership membership) {
        List<Repository> readable = new ArrayList<>();
        List<AccessGrant> grants = new ArrayList<>();
        List<SkippedRepository> skipped = new ArrayList<>();

        for (RepositoryResult result : results) {
            if (result.skipReason() != null) {
                skipped.add(new SkippedRepository(result.repository().name(), result.skipReason()));
                continue;
            }
            Repository repository = toRepository(result.repository());
            readable.add(repository);
            for (CollaboratorJson collaborator : result.collaborators()) {
                grants.add(new AccessGrant(
                        new User(collaborator.id(), collaborator.login()),
                        repository,
                        Permission.fromGitHub(collaborator.roleName(), collaborator.permissions()),
                        collaborator.roleName(),
                        membership.affiliationOf(collaborator.id())));
            }
        }
        return new OrgAccessSnapshot(readable, grants, Coverage.of(listed, skipped, membership.known()));
    }

    private static Repository toRepository(RepositoryJson json) {
        return new Repository(json.id(), json.name(),
                Visibility.fromGitHub(json.visibility(), json.privateRepository()),
                json.archivedRepository(), json.forkRepository(), json.pushedAt());
    }

    private record RepositoryResult(RepositoryJson repository, List<CollaboratorJson> collaborators,
            SkippedRepository.Reason skipReason) {}

    private record Membership(Optional<Set<Long>> ownerIds, Optional<Set<Long>> outsideCollaboratorIds) {

        boolean known() {
            return ownerIds.isPresent() && outsideCollaboratorIds.isPresent();
        }

        Affiliation affiliationOf(long userId) {
            if (ownerIds.map(ids -> ids.contains(userId)).orElse(false)) {
                return Affiliation.OWNER;
            }
            if (outsideCollaboratorIds.map(ids -> ids.contains(userId)).orElse(false)) {
                return Affiliation.OUTSIDE_COLLABORATOR;
            }
            // Anyone else on an org repository is a member, but only if both lists were readable.
            return known() ? Affiliation.MEMBER : Affiliation.UNKNOWN;
        }
    }
}
