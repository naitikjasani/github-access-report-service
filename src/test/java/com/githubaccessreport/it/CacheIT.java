package com.githubaccessreport.it;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.githubaccessreport.config.CacheConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "report.cache-ttl=1s")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext
class CacheIT {

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
        // Slow the first call down so concurrent requests really overlap.
        github.stubFor(WireMock.get(urlPathEqualTo("/orgs/acme/repos"))
                .willReturn(WireMock.okJson(GitHubStubs.fixture("repos.json")).withFixedDelay(300)));
    }

    @Test
    void concurrentRequestsForOneOrganizationTriggerASingleFetch() throws Exception {
        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> responses = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String path = i % 2 == 0 ? "/api/v1/orgs/acme/access/users" : "/api/v1/orgs/Acme/access/repositories";
                responses.add(clients.submit(
                        () -> mvc.perform(get(path)).andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> response : responses) {
                assertThat(response.get()).isEqualTo(200);
            }
        }

        github.verify(1, getRequestedFor(urlPathEqualTo("/orgs/acme/repos")));
        github.verify(1, getRequestedFor(urlPathEqualTo("/repos/acme/web/collaborators")));
    }

    @Test
    void entriesExpireAfterTtl() throws Exception {
        mvc.perform(get("/api/v1/orgs/acme/access/users")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/orgs/acme/access/users")).andExpect(status().isOk());
        github.verify(1, getRequestedFor(urlPathEqualTo("/orgs/acme/repos")));

        Thread.sleep(1_500);
        mvc.perform(get("/api/v1/orgs/acme/access/users")).andExpect(status().isOk());

        github.verify(2, getRequestedFor(urlPathEqualTo("/orgs/acme/repos")));
    }
}
