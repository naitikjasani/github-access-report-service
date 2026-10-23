package com.githubaccessreport.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.githubaccessreport.report.AccessReportService;
import com.githubaccessreport.report.ReportFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ReportController.class, properties = "report.allowed-orgs=Acme, demo-org")
@Import(ReportControllerTest.FixedClock.class)
class OrganizationAllowlistTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccessReportService reports;

    @Test
    void allowListedOrganizationIsServedCaseInsensitively() throws Exception {
        when(reports.getReport("acme")).thenReturn(ReportFixtures.report());

        mvc.perform(get("/api/v1/orgs/ACME/access/users")).andExpect(status().isOk());
    }

    @Test
    void otherOrganizationsAreRejectedBeforeAnyGitHubCall() throws Exception {
        mvc.perform(get("/api/v1/orgs/someone-else/access/users"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("organization-not-allowed"));

        org.mockito.Mockito.verifyNoInteractions(reports);
    }
}
