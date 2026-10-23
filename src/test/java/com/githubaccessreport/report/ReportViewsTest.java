package com.githubaccessreport.report;

import static com.githubaccessreport.report.ReportFixtures.report;
import static org.assertj.core.api.Assertions.assertThat;

import com.githubaccessreport.report.domain.Affiliation;
import com.githubaccessreport.report.domain.Permission;
import com.githubaccessreport.report.view.AccessFilter;
import com.githubaccessreport.report.view.PageResult;
import com.githubaccessreport.report.view.ReportViews;
import com.githubaccessreport.report.view.RepositoryAccess;
import com.githubaccessreport.report.view.UserAccess;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportViewsTest {

    @Test
    void byUserGroupsGrantsPerUserInOrder() {
        List<UserAccess> users = ReportViews.byUser(report(), AccessFilter.NONE);

        assertThat(users).extracting(UserAccess::login).containsExactly("alice", "bob", "olga");
        assertThat(users.get(1).repositories()).extracting(UserAccess.RepositoryGrant::name)
                .containsExactly("api", "legacy", "web");
        assertThat(users.get(2).affiliation()).isEqualTo(Affiliation.OUTSIDE_COLLABORATOR);
    }

    @Test
    void minPermissionKeepsGrantsAtOrAboveLevel() {
        List<UserAccess> admins = ReportViews.byUser(report(), new AccessFilter(Permission.ADMIN, null, true, true));

        assertThat(admins).extracting(UserAccess::login).containsExactly("alice", "bob");
        assertThat(admins.get(1).repositories()).extracting(UserAccess.RepositoryGrant::name).containsExactly("legacy");
    }

    @Test
    void affiliationFilter() {
        List<UserAccess> outside = ReportViews.byUser(report(),
                new AccessFilter(null, Affiliation.OUTSIDE_COLLABORATOR, true, true));

        assertThat(outside).extracting(UserAccess::login).containsExactly("olga");
    }

    @Test
    void archivedAndForkedRepositoriesCanBeExcluded() {
        List<UserAccess> users = ReportViews.byUser(report(), new AccessFilter(null, null, false, false));

        assertThat(users.getFirst().repositories()).extracting(UserAccess.RepositoryGrant::name).containsExactly("api");
    }

    @Test
    void byRepositoryListsEveryRepositoryWithCollaborators() {
        List<RepositoryAccess> repositories = ReportViews.byRepository(report(), AccessFilter.NONE);

        assertThat(repositories).extracting(RepositoryAccess::name).containsExactly("api", "fork-of-lib", "legacy", "web");
        assertThat(repositories.getFirst().collaborators()).extracting(RepositoryAccess.Collaborator::login)
                .containsExactly("alice", "bob", "olga");
    }

    @Test
    void byRepositoryWithGrantFilterDropsRepositoriesWithoutMatches() {
        List<RepositoryAccess> repositories = ReportViews.byRepository(report(),
                new AccessFilter(null, Affiliation.OUTSIDE_COLLABORATOR, true, true));

        assertThat(repositories).extracting(RepositoryAccess::name).containsExactly("api");
    }

    @Test
    void summaryCountsDistinctUsers() {
        var summary = ReportViews.summarize(report());

        assertThat(summary.repositories()).isEqualTo(4);
        assertThat(summary.users()).isEqualTo(3);
        assertThat(summary.outsideCollaborators()).isEqualTo(1);
        assertThat(summary.admins()).isEqualTo(2);
    }

    @Test
    void pagination() {
        List<Integer> items = List.of(1, 2, 3, 4, 5);

        assertThat(PageResult.of(items, 0, 2).items()).containsExactly(1, 2);
        assertThat(PageResult.of(items, 2, 2).items()).containsExactly(5);
        assertThat(PageResult.of(items, 2, 2).totalPages()).isEqualTo(3);
        assertThat(PageResult.of(items, 9, 2).items()).isEmpty();
        assertThat(PageResult.of(items, 9, 2).totalElements()).isEqualTo(5);
        assertThat(PageResult.of(List.of(), 0, 50).totalPages()).isZero();
    }
}
