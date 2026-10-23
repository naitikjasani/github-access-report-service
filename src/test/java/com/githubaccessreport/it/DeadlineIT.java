package com.githubaccessreport.it;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * When the report deadline passes, outstanding GitHub calls are cancelled: the caller gets a 504
 * promptly and queued calls never start.
 */
@SpringBootTest(properties = {"report.sync-deadline=1s", "github.read-timeout=30s", "github.max-concurrency=4"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext
class DeadlineIT {

    private static final int REPOSITORIES = 20;
    private static final int SLOW_MILLIS = 3_000;

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
    void deadlineCancelsOutstandingCallsAndReturns504() throws Exception {
        String repos = IntStream.rangeClosed(1, REPOSITORIES)
                .mapToObj(i -> "{\"id\": %d, \"name\": \"r%d\"}".formatted(i, i))
                .collect(Collectors.joining(",", "[", "]"));
        github.stubFor(WireMock.get(urlPathEqualTo("/orgs/slow/repos")).willReturn(okJson(repos)));
        github.stubFor(WireMock.get(urlPathMatching("/orgs/slow/(members|outside_collaborators)"))
                .willReturn(okJson("[]")));
        github.stubFor(WireMock.get(urlPathMatching("/repos/slow/.*/collaborators"))
                .willReturn(okJson("[]").withFixedDelay(SLOW_MILLIS)));

        long start = System.nanoTime();
        mvc.perform(get("/api/v1/orgs/slow/access/users"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("report-timeout"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Answered at the deadline, not after the slow calls (3 s) or the full fan-out (15 s).
        assertThat(elapsed).isLessThan(Duration.ofMillis(2_000));

        // Let any calls that escaped cancellation finish and show up in the journal.
        Thread.sleep(SLOW_MILLIS + 1_000);
        int requests = github.findAll(anyRequestedFor(anyUrl())).size();
        // repos + 2 membership + at most 4 collaborator calls in flight at the deadline; never all 20.
        assertThat(requests).isLessThanOrEqualTo(3 + 4);
    }
}
