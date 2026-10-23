package com.githubaccessreport.report;

import com.githubaccessreport.report.domain.AccessGrant;
import com.githubaccessreport.report.domain.AccessReport;
import com.githubaccessreport.report.domain.Affiliation;
import com.githubaccessreport.report.domain.Coverage;
import com.githubaccessreport.report.domain.Permission;
import com.githubaccessreport.report.domain.Repository;
import com.githubaccessreport.report.domain.User;
import com.githubaccessreport.report.domain.Visibility;
import java.time.Instant;
import java.util.List;

/** A small, fully in-memory organization used by unit tests. */
public final class ReportFixtures {

    public static final Instant GENERATED_AT = Instant.parse("2026-01-01T12:00:00Z");

    public static final Repository API = repo(1, "api", Visibility.PRIVATE, false, false);
    public static final Repository WEB = repo(2, "web", Visibility.PUBLIC, false, false);
    public static final Repository LEGACY = repo(3, "legacy", Visibility.PRIVATE, true, false);
    public static final Repository FORK = repo(4, "fork-of-lib", Visibility.PUBLIC, false, true);

    public static final User ALICE = new User(101, "alice");
    public static final User BOB = new User(201, "bob");
    public static final User OLGA = new User(301, "olga");

    private ReportFixtures() {}

    public static AccessReport report() {
        return new AccessReport("acme", GENERATED_AT, List.of(API, FORK, LEGACY, WEB),
                List.of(
                        grant(ALICE, API, Permission.ADMIN, Affiliation.OWNER),
                        grant(ALICE, FORK, Permission.ADMIN, Affiliation.OWNER),
                        grant(ALICE, LEGACY, Permission.ADMIN, Affiliation.OWNER),
                        grant(BOB, API, Permission.WRITE, Affiliation.MEMBER),
                        grant(BOB, LEGACY, Permission.ADMIN, Affiliation.MEMBER),
                        grant(BOB, WEB, Permission.TRIAGE, Affiliation.MEMBER),
                        grant(OLGA, API, Permission.WRITE, Affiliation.OUTSIDE_COLLABORATOR)),
                Coverage.of(4, List.of(), true));
    }

    public static AccessGrant grant(User user, Repository repository, Permission permission, Affiliation affiliation) {
        return new AccessGrant(user, repository, permission, permission.name().toLowerCase(), affiliation);
    }

    public static Repository repo(long id, String name, Visibility visibility, boolean archived, boolean fork) {
        return new Repository(id, name, visibility, archived, fork, Instant.parse("2025-06-01T00:00:00Z"));
    }
}
