package com.githubaccessreport.report.view;

import com.githubaccessreport.report.domain.AccessGrant;
import com.githubaccessreport.report.domain.AccessReport;
import com.githubaccessreport.report.domain.Affiliation;
import com.githubaccessreport.report.domain.Permission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the access list into an answer: which grants deserve review.
 *
 * <p>Organization owners are excluded from the admin rules: they are admin on every repository by
 * definition, so flagging them would only add noise. "Dormant" means the <em>repository</em> has
 * not been pushed to; per-user inactivity would need the Enterprise audit log.
 */
public final class AccessFindings {

    private AccessFindings() {}

    public static List<Finding> evaluate(AccessReport report, Instant now, Duration dormantAfter) {
        List<Finding> findings = new ArrayList<>();
        for (AccessGrant grant : report.grants()) {
            boolean outside = grant.affiliation() == Affiliation.OUTSIDE_COLLABORATOR;
            boolean nonOwnerAdmin = grant.permission() == Permission.ADMIN && grant.affiliation() != Affiliation.OWNER;

            if (outside && grant.permission().isAtLeast(Permission.WRITE)) {
                findings.add(finding(
                        grant,
                        Finding.Rule.OUTSIDE_COLLABORATOR_CAN_WRITE,
                        grant.permission() == Permission.ADMIN ? Finding.Severity.HIGH : Finding.Severity.MEDIUM,
                        "Outside collaborator with %s access".formatted(grant.permission())));
            }
            if (nonOwnerAdmin && grant.repository().archived()) {
                findings.add(finding(
                        grant,
                        Finding.Rule.ADMIN_ON_ARCHIVED_REPOSITORY,
                        Finding.Severity.LOW,
                        "Admin on an archived repository (admins can unarchive it)"));
            } else if (nonOwnerAdmin && isDormant(grant, now, dormantAfter)) {
                findings.add(finding(
                        grant,
                        Finding.Rule.ADMIN_ON_DORMANT_REPOSITORY,
                        Finding.Severity.LOW,
                        "Admin on a repository with no push for %d+ days".formatted(dormantAfter.toDays())));
            }
        }
        findings.sort(Comparator.comparing(Finding::severity)
                .thenComparing(Finding::login, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Finding::repository, String.CASE_INSENSITIVE_ORDER));
        return findings;
    }

    private static boolean isDormant(AccessGrant grant, Instant now, Duration dormantAfter) {
        Instant pushedAt = grant.repository().pushedAt();
        return pushedAt != null && pushedAt.isBefore(now.minus(dormantAfter));
    }

    private static Finding finding(AccessGrant grant, Finding.Rule rule, Finding.Severity severity, String why) {
        return new Finding(
                rule,
                severity,
                grant.user().login(),
                grant.user().id(),
                grant.affiliation(),
                grant.repository().name(),
                grant.permission(),
                why);
    }
}
