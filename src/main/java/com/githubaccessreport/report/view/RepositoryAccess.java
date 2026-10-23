package com.githubaccessreport.report.view;

import com.githubaccessreport.report.domain.Affiliation;
import com.githubaccessreport.report.domain.Permission;
import com.githubaccessreport.report.domain.Visibility;
import java.time.Instant;
import java.util.List;

/** Repository → users view. */
public record RepositoryAccess(
        String name,
        Visibility visibility,
        boolean archived,
        boolean fork,
        Instant pushedAt,
        List<Collaborator> collaborators) {

    public record Collaborator(
            long id, String login, Permission permission, String roleName, Affiliation affiliation) {}
}
