package com.githubaccessreport.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Log lines carry the request's trace id, the same id returned to the caller in {@code X-Trace-Id}
 * and the problem body, and never the GitHub token, even with DEBUG logging on.
 */
@SpringBootTest(
        properties = {
            "github.token=ghp_this_must_never_be_logged",
            "logging.level.com.githubaccessreport=DEBUG",
            "logging.level.org.springframework.web=DEBUG"
        })
@AutoConfigureMockMvc
@AutoConfigureTracing
@ActiveProfiles({"test", "prod"})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
class LoggingIT {

    private static final String TOKEN = "ghp_this_must_never_be_logged";

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
    void tokenNeverAppearsInLogsOnSuccessOrFailure(CapturedOutput output) throws Exception {
        GitHubStubs.acme(github);
        github.stubFor(WireMock.get(urlPathEqualTo("/orgs/revoked/repos"))
                .willReturn(aResponse().withStatus(401)));
        github.stubFor(WireMock.get(urlPathEqualTo("/orgs/broken/repos"))
                .willReturn(aResponse().withStatus(500)));

        mvc.perform(get("/api/v1/orgs/acme/access/users")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/orgs/revoked/access/users")).andExpect(status().isBadGateway());
        mvc.perform(get("/api/v1/orgs/broken/access/users")).andExpect(status().isBadGateway());

        assertThat(output.getAll())
                .contains("GitHub rejected the service token")
                .doesNotContain(TOKEN);
    }

    @Test
    void problemTraceIdMatchesResponseHeaderAndLogs(CapturedOutput output) throws Exception {
        github.stubFor(WireMock.get(urlPathEqualTo("/orgs/revoked/repos"))
                .willReturn(aResponse().withStatus(401)));

        MvcResult result = mvc.perform(get("/api/v1/orgs/revoked/access/users"))
                .andExpect(status().isBadGateway())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.traceId").exists())
                .andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");

        assertThat(result.getResponse().getContentAsString()).contains("\"traceId\":\"" + traceId + "\"");
        String logLine = output.getOut()
                .lines()
                .filter(line -> line.contains("GitHub rejected the service token"))
                .findFirst()
                .orElseThrow();
        // The format (JSON in prod) is verified against the packaged jar: Logback is initialized once
        // per JVM, so a test context cannot switch an already-initialized test JVM to JSON.
        assertThat(logLine).contains(traceId);
    }
}
