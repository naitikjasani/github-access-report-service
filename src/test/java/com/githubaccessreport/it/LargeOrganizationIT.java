package com.githubaccessreport.it;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A synthetic 1,200-repository organization: proves pagination and fan-out at scale and pins the
 * exact number of GitHub requests a report costs (the formula in the README).
 *
 * <p>Set {@code -Dbenchmark.latency-ms=150} to add per-request latency and log a timing; CI runs
 * it with no added latency and asserts only correctness and request count.
 */
@SpringBootTest(properties = "report.sync-deadline=120s")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext
class LargeOrganizationIT {

    private static final Logger log = LoggerFactory.getLogger(LargeOrganizationIT.class);

    private static final int REPOSITORIES = 1_200;
    private static final int PAGE_SIZE = 100;
    private static final int LATENCY_MS = Integer.getInteger("benchmark.latency-ms", 0);

    @RegisterExtension
    static WireMockExtension github = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().containerThreads(64))
            .build();

    @DynamicPropertySource
    static void githubBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("github.base-url", github::baseUrl);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void reportsOnTwelveHundredRepositoriesWithExactRequestCount() throws Exception {
        int pages = REPOSITORIES / PAGE_SIZE;
        for (int page = 1; page <= pages; page++) {
            var response =
                    okJson(repositories((page - 1) * PAGE_SIZE + 1, PAGE_SIZE)).withFixedDelay(LATENCY_MS);
            if (page < pages) {
                response.withHeader(
                        "Link",
                        "<" + github.baseUrl() + "/organizations/1/repos?page=" + (page + 1) + ">; rel=\"next\"");
            }
            github.stubFor(WireMock.get(urlPathEqualTo("/orgs/big/repos"))
                    .withQueryParam("page", equalTo(Integer.toString(page)))
                    .willReturn(response));
        }
        github.stubFor(WireMock.get(urlPathMatching("/orgs/big/(members|outside_collaborators)"))
                .willReturn(okJson("[]").withFixedDelay(LATENCY_MS)));
        github.stubFor(WireMock.get(urlPathMatching("/repos/big/.*/collaborators"))
                .willReturn(okJson("[{\"id\": 1, \"login\": \"dev\", \"role_name\": \"write\"}]")
                        .withFixedDelay(LATENCY_MS)));

        long start = System.nanoTime();
        mvc.perform(get("/api/v1/orgs/big/access/repositories").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage.repositoriesListed").value(REPOSITORIES))
                .andExpect(jsonPath("$.coverage.repositoriesReadable").value(REPOSITORIES))
                .andExpect(jsonPath("$.page.totalElements").value(REPOSITORIES));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // ceil(repos / 100) + 2 membership calls + one collaborators call per repository
        int expectedRequests = pages + 2 + REPOSITORIES;
        assertThat(github.findAll(anyRequestedFor(anyUrl()))).hasSize(expectedRequests);
        log.info(
                "benchmark: {} repositories, {} GitHub requests, {} ms added latency per request, {} ms total",
                REPOSITORIES,
                expectedRequests,
                LATENCY_MS,
                elapsed.toMillis());
    }

    private static String repositories(int firstId, int count) {
        return IntStream.range(firstId, firstId + count)
                .mapToObj(i -> "{\"id\": %d, \"name\": \"repo-%04d\", \"visibility\": \"private\"}".formatted(i, i))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
