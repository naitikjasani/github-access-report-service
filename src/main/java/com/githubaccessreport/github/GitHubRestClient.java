package com.githubaccessreport.github;

import com.githubaccessreport.github.wire.CollaboratorJson;
import com.githubaccessreport.github.wire.RepositoryJson;
import com.githubaccessreport.github.wire.UserJson;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

/** The GitHub REST endpoints this service calls. Transport concerns live in {@link GitHubHttp}. */
@Component
public class GitHubRestClient {

    private static final ParameterizedTypeReference<List<RepositoryJson>> REPOSITORIES =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<CollaboratorJson>> COLLABORATORS =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<UserJson>> USERS = new ParameterizedTypeReference<>() {};

    private final GitHubHttp http;

    public GitHubRestClient(GitHubHttp http) {
        this.http = http;
    }

    /** All repositories of the organization that the token can see (public, private, internal). */
    public List<RepositoryJson> listRepositories(String org) {
        return http.getAllPages("/orgs/{org}/repos?type=all", REPOSITORIES, org);
    }

    /**
     * Everyone with access to the repository: outside collaborators, direct members, team members
     * and organization owners. Requires the token to have at least write access to the repository.
     */
    public List<CollaboratorJson> listCollaborators(String org, String repo) {
        return http.getAllPages("/repos/{owner}/{repo}/collaborators?affiliation=all", COLLABORATORS, org, repo);
    }

    public List<UserJson> listOutsideCollaborators(String org) {
        return http.getAllPages("/orgs/{org}/outside_collaborators", USERS, org);
    }

    public List<UserJson> listOwners(String org) {
        return http.getAllPages("/orgs/{org}/members?role=admin", USERS, org);
    }
}
