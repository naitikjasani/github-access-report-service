package com.githubaccessreport.github;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.githubaccessreport.github.wire.RepositoryJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.core.ParameterizedTypeReference;

/** Contract tests: real GitHub payloads (see github-fixtures/recorded/README.md) through the real client. */
class RecordedResponsesTest {

    @RegisterExtension
    static WireMockExtension github = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void parsesRealRepositoryPayload() throws IOException {
        github.stubFor(get(urlPathEqualTo("/orgs/octo-org/repos")).willReturn(okJson(read("octo-org-repos.json"))));
        GitHubHttp http = GitHubTestSupport.http(
                GitHubTestSupport.properties(github.baseUrl(), 2, Duration.ofSeconds(5)), new RateLimitTracker());

        List<RepositoryJson> repositories =
                http.getAllPages("/orgs/{org}/repos", new ParameterizedTypeReference<>() {}, "octo-org");

        assertThat(repositories).hasSize(3);
        assertThat(repositories).allSatisfy(repository -> {
            assertThat(repository.id()).isPositive();
            assertThat(repository.name()).isNotBlank();
            assertThat(repository.visibility()).isEqualTo("public");
            assertThat(repository.pushedAt()).isNotNull();
        });
        assertThat(repositories).extracting(RepositoryJson::forkRepository).contains(true, false);
    }

    @Test
    void readsNextPageFromRealLinkHeader() throws IOException {
        // GitHub links to /organizations/{id}/repos, not /orgs/{login}/repos: only the page number is reused.
        assertThat(LinkHeader.nextPage(read("octo-org-repos.link").strip())).hasValue(2);
    }

    private static String read(String name) throws IOException {
        try (InputStream in = RecordedResponsesTest.class.getResourceAsStream("/github-fixtures/recorded/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
