package com.githubaccessreport.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** An organization too slow for the synchronous deadline still gets a report through a job. */
@SpringBootTest(properties = {"report.sync-deadline=1s", "report.async-deadline=30s", "github.read-timeout=10s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext
class AsyncReportIT {

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
    void slowOrganizationTimesOutSynchronouslyButSucceedsAsAJob() throws Exception {
        GitHubStubs.acme(github);
        github.stubFor(WireMock.get(urlPathMatching("/repos/acme/(web|legacy|fork-of-lib)/collaborators"))
                .willReturn(okJson("[]").withFixedDelay(1_500)));

        mvc.perform(get("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("POST /api/v1/reports")));

        String location = mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organization\": \"ACME\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.organization").value("acme"))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        String job = awaitTerminal(location);
        assertThat(JsonPath.<String>read(job, "$.status")).isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<String>read(job, "$.links.users")).isEqualTo("/api/v1/orgs/acme/access/users");

        int callsAfterJob = github.findAll(anyRequestedFor(anyUrl())).size();
        mvc.perform(get("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage.repositoriesListed").value(5));
        assertThat(github.findAll(anyRequestedFor(anyUrl()))).hasSize(callsAfterJob);

        // A fresh cached report means a new job completes at once, without GitHub.
        mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organization\": \"acme\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(github.findAll(anyRequestedFor(anyUrl()))).hasSize(callsAfterJob);
    }

    @Test
    void submittingTwiceWhileRunningReturnsTheSameJob() throws Exception {
        github.stubFor(WireMock.get(urlPathEqualTo("/orgs/twice/repos"))
                .willReturn(okJson("[]").withFixedDelay(800)));
        github.stubFor(
                WireMock.get(urlPathMatching("/orgs/twice/.*")).atPriority(10).willReturn(okJson("[]")));

        String first = submit("twice");
        String second = submit("twice");

        assertThat(JsonPath.<String>read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        github.verify(1, WireMock.getRequestedFor(urlPathEqualTo("/orgs/twice/repos")));
    }

    @Test
    void failedJobCarriesTheSameProblemAsASynchronousCall() throws Exception {
        github.stubFor(WireMock.get(urlPathEqualTo("/orgs/ghost/repos"))
                .willReturn(aResponse().withStatus(404)));

        String job = awaitTerminal("/api/v1/reports/" + JsonPath.read(submit("ghost"), "$.id"));

        assertThat(JsonPath.<String>read(job, "$.status")).isEqualTo("FAILED");
        assertThat(JsonPath.<String>read(job, "$.problem.code")).isEqualTo("organization-not-found");
        assertThat(JsonPath.<Integer>read(job, "$.problem.status")).isEqualTo(404);
    }

    @Test
    void unknownJobIs404AndBadIdIs400() throws Exception {
        mvc.perform(get("/api/v1/reports/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("report-job-not-found"));
        mvc.perform(get("/api/v1/reports/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void invalidOrganizationIsRejected() throws Exception {
        mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organization\": \"bad--name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
    }

    private String submit(String organization) throws Exception {
        return mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organization\": \"" + organization + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String awaitTerminal(String location) throws Exception {
        Instant giveUp = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(giveUp)) {
            String body = mvc.perform(get(location))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String state = JsonPath.read(body, "$.status");
            if (state.equals("SUCCEEDED") || state.equals("FAILED")) {
                return body;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job did not finish: " + location);
    }
}
