package com.githubaccessreport.report.view;

import com.githubaccessreport.report.domain.Affiliation;
import com.githubaccessreport.report.domain.Permission;

/** One piece of access worth a second look, with the reason in plain words. */
public record Finding(
        Rule rule,
        Severity severity,
        String login,
        long userId,
        Affiliation affiliation,
        String repository,
        Permission permission,
        String why) {

    public enum Rule {
        /** Someone outside the organization can change code. */
        OUTSIDE_COLLABORATOR_CAN_WRITE,
        /** Admin on an archived repository: it can be unarchived and changed. */
        ADMIN_ON_ARCHIVED_REPOSITORY,
        /** Admin on a repository nobody has pushed to for a long time. */
        ADMIN_ON_DORMANT_REPOSITORY
    }

    public enum Severity {
        HIGH,
        MEDIUM,
        LOW
    }
}
