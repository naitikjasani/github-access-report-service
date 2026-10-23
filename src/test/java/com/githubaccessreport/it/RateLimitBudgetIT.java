package com.githubaccessreport.it;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** A report the remaining quota cannot finish is refused before any per-repository call is made. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext
class RateLimitBudgetIT {

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

    @Test
    void refusesWhenRemainingQuotaCannotCoverTheReport() throws Exception {
        long resetIn10Minutes = Instant.now().plusSeconds(600).getEpochSecond();
        github.stubFor(WireMock.get(urlPathEqualTo("/orgs/acme/repos"))
                .willReturn(okJson(GitHubStubs.fixture("repos.json"))
                        .withHeader("x-ratelimit-limit", "5000")
                        .withHeader("x-ratelimit-remaining", "3")
                        .withHeader("x-ratelimit-reset", Long.toString(resetIn10Minutes))
                        .withHeader("x-ratelimit-resource", "core")));

        mvc.perform(get("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate-limit-budget"))
                .andExpect(jsonPath("$.requiredRequests").value(7)) // 5 repositories + 2 membership calls
                .andExpect(jsonPath("$.remainingRequests").value(3));

        github.verify(0, getRequestedFor(urlPathMatching("/repos/.*")));
    }
}
