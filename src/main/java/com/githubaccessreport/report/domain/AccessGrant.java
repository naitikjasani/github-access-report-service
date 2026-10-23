package com.githubaccessreport.report.domain;

/**
 * One user's effective access to one repository: the canonical row every view is derived from.
 *
 * @param roleName GitHub's role name, which may be a custom role; {@code permission} is its base level
 */
public record AccessGrant(User user, Repository repository, Permission permission, String roleName,
        Affiliation affiliation) {}
