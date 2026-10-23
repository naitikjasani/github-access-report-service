package com.githubaccessreport.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.githubaccessreport.config.ReportProperties;
import com.githubaccessreport.github.GitHubException;
import com.githubaccessreport.report.AccessReportService;
import com.githubaccessreport.report.OrganizationNotFoundException;
import com.githubaccessreport.report.RateLimitBudgetExceededException;
import com.githubaccessreport.report.ReportFixtures;
import com.githubaccessreport.report.ReportTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReportController.class)
@Import(ReportControllerTest.FixedClock.class)
class ReportControllerTest {

    static final Instant NOW = ReportFixtures.GENERATED_AT.plusSeconds(90);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccessReportService reports;

    @Test
    void usersViewReturnsEnvelopeWithCoverageSummaryAndPage() throws Exception {
        when(reports.getReport("acme")).thenReturn(ReportFixtures.report());

        mvc.perform(get("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization").value("acme"))
                .andExpect(jsonPath("$.ageSeconds").value(90))
                .andExpect(jsonPath("$.coverage.complete").value(true))
                .andExpect(jsonPath("$.summary.users").value(3))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.items[0].login").value("alice"))
                .andExpect(jsonPath("$.items[0].affiliation").value("OWNER"))
                .andExpect(jsonPath("$.items[0].repositories[0].name").value("api"))
                .andExpect(jsonPath("$.items[0].repositories[0].permission").value("ADMIN"));
    }

    @Test
    void organizationIsNormalizedToLowerCase() throws Exception {
        when(reports.getReport("acme")).thenReturn(ReportFixtures.report());

        mvc.perform(get("/api/v1/orgs/ACME/access/users")).andExpect(status().isOk());

        verify(reports).getReport("acme");
    }

    @Test
    void repositoriesViewAppliesFiltersAndPaging() throws Exception {
        when(reports.getReport("acme")).thenReturn(ReportFixtures.report());

        mvc.perform(get("/api/v1/orgs/acme/access/repositories")
                        .param("minPermission", "WRITE")
                        .param("size", "1")
                        .param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(3)) // api, fork-of-lib, legacy; web has no WRITE+
                .andExpect(jsonPath("$.page.totalPages").value(3))
                .andExpect(jsonPath("$.items[0].name").value("legacy"))
                .andExpect(jsonPath("$.items[0].archived").value(true));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "-leading",
                "trailing-",
                "double--hyphen",
                "under_score",
                "dot.name",
                "a234567890123456789012345678901234567890"
            })
    void invalidOrganizationNamesAreRejected(String organization) throws Exception {
        mvc.perform(get("/api/v1/orgs/{org}/access/users", organization))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));

        org.mockito.Mockito.verifyNoInteractions(reports);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "my-org", "Org123", "a23456789012345678901234567890123456789"})
    void validOrganizationNamesAreAccepted(String organization) throws Exception {
        when(reports.getReport(anyString())).thenReturn(ReportFixtures.report());

        mvc.perform(get("/api/v1/orgs/{org}/access/users", organization)).andExpect(status().isOk());
    }

    @Test
    void invalidPageSizeIsAProblem400() throws Exception {
        mvc.perform(get("/api/v1/orgs/acme/access/users").param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("invalid-request"))
                .andExpect(jsonPath("$.detail").value("size must be less than or equal to 200"));
    }

    @Test
    void invalidEnumIsAProblem400() throws Exception {
        mvc.perform(get("/api/v1/orgs/acme/access/users").param("minPermission", "superuser"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
    }

    @Test
    void unknownPathIs404NotA500() throws Exception {
        mvc.perform(get("/api/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void wrongMethodIs405() throws Exception {
        mvc.perform(post("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("method-not-allowed"));
    }

    @Test
    void organizationNotFound() throws Exception {
        when(reports.getReport("missing")).thenThrow(new OrganizationNotFoundException("missing"));

        mvc.perform(get("/api/v1/orgs/missing/access/users"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("organization-not-found"))
                .andExpect(jsonPath("$.detail").value("GitHub organization not found: missing"))
                .andExpect(jsonPath("$.instance").value("/api/v1/orgs/missing/access/users"));
    }

    @Test
    void primaryRateLimitIs429WithRetryAfter() throws Exception {
        when(reports.getReport(anyString()))
                .thenThrow(new GitHubException.PrimaryRateLimitExceeded(403, NOW.plusSeconds(600)));

        mvc.perform(get("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "600"))
                .andExpect(jsonPath("$.code").value("rate-limited"))
                .andExpect(jsonPath("$.resetAt").exists());
    }

    @Test
    void insufficientBudgetIs429WithCounts() throws Exception {
        when(reports.getReport(anyString()))
                .thenThrow(new RateLimitBudgetExceededException(1002, 40, NOW.plusSeconds(120)));

        mvc.perform(get("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "120"))
                .andExpect(jsonPath("$.code").value("rate-limit-budget"))
                .andExpect(jsonPath("$.requiredRequests").value(1002))
                .andExpect(jsonPath("$.remainingRequests").value(40));
    }

    @Test
    void secondaryRateLimitIs503WithRetryAfter() throws Exception {
        when(reports.getReport(anyString()))
                .thenThrow(new GitHubException.SecondaryRateLimitExceeded(429, Duration.ofSeconds(60)));

        mvc.perform(get("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("upstream-throttled"));
    }

    @Test
    void badServiceTokenIs502NotOurCallers401() throws Exception {
        when(reports.getReport(anyString())).thenThrow(new GitHubException.Unauthorized("bad token"));

        mvc.perform(get("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("upstream-auth-failed"));
    }

    @Test
    void upstreamFailureIs502() throws Exception {
        when(reports.getReport(anyString())).thenThrow(new GitHubException.Unavailable("boom", 502, null));

        mvc.perform(get("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("upstream-unavailable"));
    }

    @Test
    void timeoutIs504() throws Exception {
        when(reports.getReport(anyString())).thenThrow(new ReportTimeoutException("acme", Duration.ofSeconds(45)));

        mvc.perform(get("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("report-timeout"));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        when(reports.getReport(anyString())).thenThrow(new IllegalStateException("internal detail"));

        mvc.perform(get("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal-error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
    }

    /** Controller collaborators that slice tests don't load: a fixed clock and the report settings. */
    @TestConfiguration
    @EnableConfigurationProperties(ReportProperties.class)
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
