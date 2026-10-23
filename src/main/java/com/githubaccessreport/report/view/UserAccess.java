package com.githubaccessreport.report.view;

import com.githubaccessreport.report.domain.Affiliation;
import com.githubaccessreport.report.domain.Permission;
import com.githubaccessreport.report.domain.Visibility;
import java.util.List;

/** User → repositories view. */
public record UserAccess(long id, String login, Affiliation affiliation, List<RepositoryGrant> repositories) {

    public record RepositoryGrant(String name, Permission permission, String roleName, Visibility visibility,
            boolean archived, boolean fork) {}
}
