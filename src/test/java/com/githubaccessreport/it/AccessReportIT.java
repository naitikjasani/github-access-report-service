package com.githubaccessreport.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.githubaccessreport.config.CacheConfig;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Full application against a WireMock GitHub serving the {@code acme} fixture organization. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessReportIT {

    @RegisterExtension
    static WireMockExtension github = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void githubBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("github.base-url", github::baseUrl);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.ACCESS_REPORTS))
                .clear();
        GitHubStubs.acme(github);
    }

    @Test
    void reportsAccessByUserWithAffiliationsCustomRolesAndCoverage() throws Exception {
        mvc.perform(get("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].login", contains("alice", "bob", "carol", "olga")))
                .andExpect(jsonPath("$.items[0].affiliation").value("OWNER"))
                .andExpect(jsonPath("$.items[1].affiliation").value("MEMBER"))
                .andExpect(jsonPath("$.items[3].affiliation").value("OUTSIDE_COLLABORATOR"))
                // bob: custom role on web keeps its name and maps to its base level
                .andExpect(jsonPath("$.items[1].repositories[*].name", contains("api", "legacy", "web")))
                .andExpect(jsonPath("$.items[1].repositories[2].permission").value("TRIAGE"))
                .andExpect(jsonPath("$.items[1].repositories[2].roleName").value("security-reviewer"))
                // carol only appears on page 2 of api's collaborators
                .andExpect(jsonPath("$.items[2].repositories[0].name").value("api"))
                // the 403 repository is reported, not silently dropped
                .andExpect(jsonPath("$.coverage.complete").value(false))
                .andExpect(jsonPath("$.coverage.repositoriesListed").value(5))
                .andExpect(jsonPath("$.coverage.repositoriesReadable").value(4))
                .andExpect(jsonPath("$.coverage.skippedRepositories[0].name").value("secret"))
                .andExpect(jsonPath("$.coverage.skippedRepositories[0].reason").value("INSUFFICIENT_PERMISSION"))
                .andExpect(jsonPath("$.summary.users").value(4))
                .andExpect(jsonPath("$.summary.outsideCollaborators").value(1))
                .andExpect(jsonPath("$.summary.admins").value(2));
    }

    @Test
    void outsideCollaboratorsWithWriteAccess() throws Exception {
        mvc.perform(get("/api/v1/orgs/acme/access/users")
                        .param("affiliation", "OUTSIDE_COLLABORATOR")
                        .param("minPermission", "WRITE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].login", contains("olga")))
                .andExpect(jsonPath("$.items[0].repositories[0].permission").value("WRITE"));
    }

    @Test
    void repositoryViewFlagsArchivedAndForkedRepositories() throws Exception {
        mvc.perform(get("/api/v1/orgs/acme/access/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].name", contains("api", "fork-of-lib", "legacy", "web")))
                .andExpect(jsonPath("$.items[1].fork").value(true))
                .andExpect(jsonPath("$.items[2].archived").value(true))
                .andExpect(jsonPath("$.items[0].visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.items[0].collaborators[*].login", contains("alice", "bob", "carol", "olga")));
    }

    @Test
    void secondRequestIsServedFromCache() throws Exception {
        mvc.perform(get("/api/v1/orgs/acme/access/users")).andExpect(status().isOk());
        int callsAfterFirst = github.findAll(anyRequestedFor(anyUrl())).size();

        mvc.perform(get("/api/v1/orgs/ACME/access/repositories")).andExpect(status().isOk());

        // 1 repo list + 2 membership + 5 repos (api has 2 pages) = 9, and no more after a cache hit
        assertThat(callsAfterFirst).isEqualTo(9);
        assertThat(github.findAll(anyRequestedFor(anyUrl()))).hasSize(callsAfterFirst);
    }

    @Test
    void unknownOrganizationIs404Problem() throws Exception {
        github.stubFor(WireMock.get(urlPathEqualTo("/orgs/nope/repos"))
                .willReturn(aResponse().withStatus(404)));

        mvc.perform(get("/api/v1/orgs/nope/access/users"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("organization-not-found"));
    }

    @Test
    void invalidServiceTokenIs502() throws Exception {
        github.stubFor(WireMock.get(urlPathEqualTo("/orgs/badtoken/repos"))
                .willReturn(aResponse().withStatus(401)));

        mvc.perform(get("/api/v1/orgs/badtoken/access/users"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("upstream-auth-failed"));
    }

    @Test
    void persistentGitHubOutageIs502AfterRetries() throws Exception {
        github.stubFor(WireMock.get(urlPathEqualTo("/repos/acme/web/collaborators"))
                .willReturn(aResponse().withStatus(500)));

        mvc.perform(get("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("upstream-unavailable"));

        github.verify(3, getRequestedFor(urlPathEqualTo("/repos/acme/web/collaborators")));
    }
}
