package com.githubaccessreport.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Stubs the GitHub endpoints for the {@code acme} fixture organization. */
final class GitHubStubs {

    private GitHubStubs() {}

    static void acme(WireMockExtension github) {
        github.stubFor(get(urlPathEqualTo("/orgs/acme/repos")).willReturn(okJson(fixture("repos.json"))
                .withHeader("x-ratelimit-limit", "5000")
                .withHeader("x-ratelimit-remaining", "4990")
                .withHeader("x-ratelimit-reset", "4102444800")
                .withHeader("x-ratelimit-resource", "core")));
        github.stubFor(get(urlPathEqualTo("/orgs/acme/members")).withQueryParam("role", equalTo("admin"))
                .willReturn(okJson(fixture("owners.json"))));
        github.stubFor(get(urlPathEqualTo("/orgs/acme/outside_collaborators"))
                .willReturn(okJson(fixture("outside_collaborators.json"))));

        github.stubFor(get(urlPathEqualTo("/repos/acme/api/collaborators")).withQueryParam("page", equalTo("1"))
                .willReturn(okJson(fixture("collaborators/api-page1.json")).withHeader("Link",
                        "<" + github.baseUrl() + "/repositories/1/collaborators?affiliation=all&per_page=100&page=2>; rel=\"next\"")));
        github.stubFor(get(urlPathEqualTo("/repos/acme/api/collaborators")).withQueryParam("page", equalTo("2"))
                .willReturn(okJson(fixture("collaborators/api-page2.json"))));
        for (String repo : new String[] {"web", "legacy", "fork-of-lib"}) {
            github.stubFor(get(urlPathEqualTo("/repos/acme/" + repo + "/collaborators"))
                    .willReturn(okJson(fixture("collaborators/" + repo + ".json"))));
        }
        github.stubFor(get(urlPathEqualTo("/repos/acme/secret/collaborators")).willReturn(aResponse()
                .withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withHeader("x-ratelimit-remaining", "4980")
                .withBody("{\"message\":\"Must have push access to view repository collaborators.\"}")));
    }

    static String fixture(String path) {
        try (InputStream in = GitHubStubs.class.getResourceAsStream("/github-fixtures/acme/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
