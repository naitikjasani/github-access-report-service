package com.githubaccessreport.report;

import static com.githubaccessreport.report.ReportFixtures.ALICE;
import static com.githubaccessreport.report.ReportFixtures.BOB;
import static org.assertj.core.api.Assertions.assertThat;

import com.githubaccessreport.report.domain.AccessReport;
import com.githubaccessreport.report.domain.Affiliation;
import com.githubaccessreport.report.domain.Coverage;
import com.githubaccessreport.report.domain.Permission;
import com.githubaccessreport.report.domain.Repository;
import com.githubaccessreport.report.domain.User;
import com.githubaccessreport.report.domain.Visibility;
import com.githubaccessreport.report.view.AccessFindings;
import com.githubaccessreport.report.view.Finding;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccessFindingsTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration DORMANT = Duration.ofDays(180);

    @Test
    void flagsOutsideWritersAndAdminsOnArchivedRepositories() {
        List<Finding> findings = AccessFindings.evaluate(ReportFixtures.report(), NOW, DORMANT);

        assertThat(findings)
                .extracting(f -> f.rule() + ":" + f.login() + "@" + f.repository() + ":" + f.severity())
                .containsExactly(
                        "OUTSIDE_COLLABORATOR_CAN_WRITE:olga@api:MEDIUM",
                        "ADMIN_ON_ARCHIVED_REPOSITORY:bob@legacy:LOW");
    }

    @Test
    void ownersAreNotFlaggedForBeingAdmin() {
        assertThat(AccessFindings.evaluate(ReportFixtures.report(), NOW, DORMANT))
                .noneMatch(f -> f.login().equals(ALICE.login()));
    }

    @Test
    void dormantMeansNoPushForTheWholeWindow() {
        Repository stale =
                new Repository(10, "stale", Visibility.PRIVATE, false, false, NOW.minus(Duration.ofDays(200)));
        Repository active =
                new Repository(11, "active", Visibility.PRIVATE, false, false, NOW.minus(Duration.ofDays(10)));
        AccessReport report = new AccessReport(
                "acme",
                NOW,
                List.of(active, stale),
                List.of(
                        ReportFixtures.grant(BOB, active, Permission.ADMIN, Affiliation.MEMBER),
                        ReportFixtures.grant(BOB, stale, Permission.ADMIN, Affiliation.MEMBER)),
                Coverage.of(2, List.of(), true));

        assertThat(AccessFindings.evaluate(report, NOW, DORMANT))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.rule()).isEqualTo(Finding.Rule.ADMIN_ON_DORMANT_REPOSITORY);
                    assertThat(f.repository()).isEqualTo("stale");
                });
    }

    @Test
    void outsideAdminIsHighSeverity() {
        User eve = new User(999, "eve");
        AccessReport report = new AccessReport(
                "acme",
                NOW,
                List.of(ReportFixtures.API),
                List.of(ReportFixtures.grant(
                        eve, ReportFixtures.API, Permission.ADMIN, Affiliation.OUTSIDE_COLLABORATOR)),
                Coverage.of(1, List.of(), true));

        assertThat(AccessFindings.evaluate(report, NOW, DORMANT))
                .first()
                .extracting(Finding::severity)
                .isEqualTo(Finding.Severity.HIGH);
    }
}
