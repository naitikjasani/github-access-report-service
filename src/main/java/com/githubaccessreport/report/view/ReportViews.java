package com.githubaccessreport.report.view;

import com.githubaccessreport.report.domain.AccessGrant;
import com.githubaccessreport.report.domain.AccessReport;
import com.githubaccessreport.report.domain.Affiliation;
import com.githubaccessreport.report.domain.Permission;
import com.githubaccessreport.report.domain.Repository;
import com.githubaccessreport.report.domain.User;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Pure functions from a cached report to the shapes the API returns. Grants arrive pre-sorted. */
public final class ReportViews {

    private ReportViews() {}

    public static List<UserAccess> byUser(AccessReport report, AccessFilter filter) {
        Map<Long, List<AccessGrant>> grantsByUser = report.grants().stream()
                .filter(filter::matches)
                .collect(Collectors.groupingBy(grant -> grant.user().id(), LinkedHashMap::new, Collectors.toList()));

        List<UserAccess> users = new ArrayList<>(grantsByUser.size());
        for (List<AccessGrant> grants : grantsByUser.values()) {
            AccessGrant first = grants.getFirst();
            users.add(new UserAccess(
                    first.user().id(),
                    first.user().login(),
                    first.affiliation(),
                    grants.stream()
                            .map(grant -> new UserAccess.RepositoryGrant(
                                    grant.repository().name(),
                                    grant.permission(),
                                    grant.roleName(),
                                    grant.repository().visibility(),
                                    grant.repository().archived(),
                                    grant.repository().fork()))
                            .toList()));
        }
        return users;
    }

    /**
     * Every readable repository that passes the repository filters, each with its matching
     * collaborators. When a grant-level filter is set, repositories with no match are left out.
     */
    public static List<RepositoryAccess> byRepository(AccessReport report, AccessFilter filter) {
        Map<Long, List<RepositoryAccess.Collaborator>> collaborators = new LinkedHashMap<>();
        for (AccessGrant grant : report.grants()) {
            if (filter.matches(grant)) {
                collaborators
                        .computeIfAbsent(grant.repository().id(), id -> new ArrayList<>())
                        .add(new RepositoryAccess.Collaborator(
                                grant.user().id(),
                                grant.user().login(),
                                grant.permission(),
                                grant.roleName(),
                                grant.affiliation()));
            }
        }
        return report.repositories().stream()
                .filter(filter::matches)
                .filter(repository -> !filter.filtersGrants() || collaborators.containsKey(repository.id()))
                .map(repository ->
                        toRepositoryAccess(repository, collaborators.getOrDefault(repository.id(), List.of())))
                .toList();
    }

    public static ReportSummary summarize(AccessReport report) {
        var users = report.grants().stream()
                .map(AccessGrant::user)
                .map(User::id)
                .distinct()
                .count();
        var outside = report.grants().stream()
                .filter(grant -> grant.affiliation() == Affiliation.OUTSIDE_COLLABORATOR)
                .map(grant -> grant.user().id())
                .distinct()
                .count();
        var admins = report.grants().stream()
                .filter(grant -> grant.permission() == Permission.ADMIN)
                .map(grant -> grant.user().id())
                .distinct()
                .count();
        return new ReportSummary(report.repositories().size(), (int) users, (int) outside, (int) admins);
    }

    private static RepositoryAccess toRepositoryAccess(
            Repository repository, List<RepositoryAccess.Collaborator> collaborators) {
        return new RepositoryAccess(
                repository.name(),
                repository.visibility(),
                repository.archived(),
                repository.fork(),
                repository.pushedAt(),
                List.copyOf(collaborators));
    }
}
