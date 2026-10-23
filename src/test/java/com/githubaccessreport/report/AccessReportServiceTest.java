package com.githubaccessreport.report;

import static com.githubaccessreport.report.ReportFixtures.ALICE;
import static com.githubaccessreport.report.ReportFixtures.API;
import static com.githubaccessreport.report.ReportFixtures.BOB;
import static com.githubaccessreport.report.ReportFixtures.WEB;
import static com.githubaccessreport.report.ReportFixtures.grant;
import static org.assertj.core.api.Assertions.assertThat;

import com.githubaccessreport.config.ReportProperties;
import com.githubaccessreport.report.domain.AccessGrant;
import com.githubaccessreport.report.domain.AccessReport;
import com.githubaccessreport.report.domain.Affiliation;
import com.githubaccessreport.report.domain.Coverage;
import com.githubaccessreport.report.domain.OrgAccessSnapshot;
import com.githubaccessreport.report.domain.Permission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AccessReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private final AtomicReference<Instant> deadlineSeen = new AtomicReference<>();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ReportProperties properties =
            new ReportProperties(Duration.ofSeconds(30), Duration.ofMinutes(10), 100, Set.of());

    @Test
    void sortsGrantsAndRepositoriesAndPassesDeadline() {
        var service = new AccessReportService(source(new OrgAccessSnapshot(List.of(WEB, API),
                List.of(grant(BOB, WEB, Permission.READ, Affiliation.MEMBER),
                        grant(ALICE, WEB, Permission.ADMIN, Affiliation.OWNER),
                        grant(ALICE, API, Permission.ADMIN, Affiliation.OWNER)),
                Coverage.of(2, List.of(), true))), properties, clock);

        AccessReport report = service.getReport("acme");

        assertThat(report.repositories()).extracting(r -> r.name()).containsExactly("api", "web");
        assertThat(report.grants()).extracting(g -> g.user().login() + "/" + g.repository().name())
                .containsExactly("alice/api", "alice/web", "bob/web");
        assertThat(report.generatedAt()).isEqualTo(NOW);
        assertThat(deadlineSeen.get()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void duplicateGrantsKeepHighestPermission() {
        var service = new AccessReportService(source(new OrgAccessSnapshot(List.of(API),
                List.of(grant(BOB, API, Permission.READ, Affiliation.MEMBER),
                        grant(BOB, API, Permission.WRITE, Affiliation.MEMBER)),
                Coverage.of(1, List.of(), true))), properties, clock);

        List<AccessGrant> grants = service.getReport("acme").grants();

        assertThat(grants).singleElement().extracting(AccessGrant::permission).isEqualTo(Permission.WRITE);
    }

    @Test
    void emptyOrganizationIsACompleteEmptyReport() {
        var service = new AccessReportService(
                source(new OrgAccessSnapshot(List.of(), List.of(), Coverage.of(0, List.of(), true))), properties, clock);

        AccessReport report = service.getReport("empty");

        assertThat(report.grants()).isEmpty();
        assertThat(report.coverage().complete()).isTrue();
    }

    private OrgAccessSource source(OrgAccessSnapshot snapshot) {
        return (organization, deadline) -> {
            deadlineSeen.set(deadline);
            return snapshot;
        };
    }
}
