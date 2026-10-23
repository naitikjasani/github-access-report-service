package com.githubaccessreport.report.view;

import com.githubaccessreport.report.domain.AccessGrant;
import com.githubaccessreport.report.domain.Affiliation;
import com.githubaccessreport.report.domain.Permission;
import com.githubaccessreport.report.domain.Repository;

/**
 * Optional filters applied to a cached report.
 *
 * @param minPermission only grants at or above this level (null: all, including UNKNOWN)
 * @param affiliation   only users with this affiliation (null: all)
 */
public record AccessFilter(Permission minPermission, Affiliation affiliation, boolean includeArchived,
        boolean includeForks) {

    public static final AccessFilter NONE = new AccessFilter(null, null, true, true);

    public boolean matches(Repository repository) {
        return (includeArchived || !repository.archived()) && (includeForks || !repository.fork());
    }

    public boolean matches(AccessGrant grant) {
        return matches(grant.repository())
                && (minPermission == null || grant.permission().isAtLeast(minPermission))
                && (affiliation == null || grant.affiliation() == affiliation);
    }

    boolean filtersGrants() {
        return minPermission != null || affiliation != null;
    }
}
